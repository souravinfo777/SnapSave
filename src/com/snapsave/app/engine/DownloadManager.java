package com.snapsave.app.engine;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Queue + workers for all downloads. Finalizes using the native SnapTube engine. */
public class DownloadManager {

    private static final String TAG = "DownloadManager";

    public interface Listener {
        void onDownloadsChanged();
    }

    private final Context ctx;
    private final File dir;
    private final List<DownloadTask> tasks = new CopyOnWriteArrayList<DownloadTask>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    /** Three downloads run at once; anything beyond that waits in the queue and starts
     *  automatically as soon as a slot frees up. */
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    public DownloadManager(Context c) {
        this.ctx = c.getApplicationContext();
        File base = this.ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) {
            base = this.ctx.getFilesDir();
        }
        this.dir = new File(base, "SnapSave");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public File dir() {
        return dir;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onDownloadsChanged();
                }
            }
        });
    }

    public List<DownloadTask> all() {
        return tasks;
    }

    /**
     * Tasks that still belong on the "Downloading" tab: queued, running, paused **and failed**.
     * Failed tasks must stay visible so the user sees the error and can retry — dropping them
     * made a broken download disappear from the app entirely.
     */
    public List<DownloadTask> active() {
        List<DownloadTask> out = new ArrayList<DownloadTask>();
        for (DownloadTask t : tasks) {
            if (t.isActive() || t.state == DownloadTask.State.PAUSED
                    || t.state == DownloadTask.State.FAILED) {
                out.add(t);
            }
        }
        return out;
    }

    public List<DownloadTask> completed() {
        List<DownloadTask> out = new ArrayList<DownloadTask>();
        for (DownloadTask t : tasks) {
            if (t.state == DownloadTask.State.COMPLETED) {
                out.add(t);
            }
        }
        return out;
    }

    public int activeCount() {
        int n = 0;
        for (DownloadTask t : tasks) {
            if (t.isActive()) {
                n++;
            }
        }
        return n;
    }

    public DownloadTask findById(String id) {
        for (DownloadTask t : tasks) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** Add a prepared task and start it. */
    public void submit(DownloadTask t) {
        if (t.id == null) {
            t.id = Long.toHexString(System.currentTimeMillis()) + "_" + (int) (Math.random() * 0xFFFF);
        }
        if (t.outFile == null) {
            t.outFile = unique(new File(dir, t.outName));
            t.outName = t.outFile.getName();
        }
        for (int i = 0; i < t.parts.size(); i++) {
            DownloadTask.Part p = t.parts.get(i);
            if (p.temp == null) {
                p.temp = new File(dir, t.id + "_part" + i + ".tmp");
            }
        }
        tasks.add(0, t);
        t.cancelToken = new Http.CancelToken();
        t.state = DownloadTask.State.QUEUED;
        persist();
        notifyChanged();
        pool.execute(worker(t));
    }

    private Runnable worker(final DownloadTask task) {
        return new Runnable() {
            @Override
            public void run() {
                runTask(task);
            }
        };
    }

    private void runTask(DownloadTask task) {
        try {
            task.state = DownloadTask.State.DOWNLOADING;
            notifyChanged();
            final int nid = Notifier.idFor(task.id);
            Notifier.progress(ctx, nid, task.title, 0);

            final long[] win = {System.currentTimeMillis(), 0L};

            for (final DownloadTask.Part part : task.parts) {
                if (task.cancelToken.cancelled) {
                    throw new Http.Cancelled();
                }
                Http.download(part.url, part.temp, part.referer,
                        part.ua == null ? Http.UA : part.ua, new Http.Progress() {
                    @Override
                    public void onProgress(long done, long total) {
                        part.done = done;
                        if (total > 0) {
                            part.total = total;
                        }
                        long now = System.currentTimeMillis();
                        long dt = now - win[0];
                        if (dt >= 1000) {
                            long bytes = task.done() - win[1];
                            if (bytes > 0) {
                                task.speed = bytes * 1000.0 / dt;
                            }
                            win[0] = now;
                            win[1] = task.done();
                            Notifier.progress(ctx, nid, task.title, task.percent());
                        }
                        notifyChanged();
                    }
                }, task.cancelToken);
            }

            // ---- finalize with the native engine ----
            File target = task.outFile;
            if ("video".equals(task.kind) && task.parts.size() >= 2) {
                task.state = DownloadTask.State.MERGING;
                notifyChanged();
                File video = task.parts.get(0).temp;
                File audio = task.parts.get(1).temp;
                if (NativeStreams.available()) {
                    boolean ok = NativeStreams.mux(audio, video, target);
                    if (!ok) {
                        // mux failed: fall back to the video track only so the user keeps something usable
                        copy(video, target);
                        task.error = "merge_failed";
                    }
                } else {
                    copy(video, target);
                    task.error = "native_engine_unavailable";
                }
            } else if ("mp3".equals(task.kind)) {
                task.state = DownloadTask.State.CONVERTING;
                notifyChanged();
                File src = task.parts.get(0).temp;
                if (NativeStreams.available() && NativeStreams.toMp3(src, target, task.mp3Bitrate)) {
                    // done by native transcoder
                } else if (looksLikeVideo(task)) {
                    // transcoding failed for a video source — keep it as a playable mp4,
                    // never hand back a video file mislabelled as .mp3
                    File v = new File(dir, stripExt(task.outName) + ".mp4");
                    copy(src, v);
                    task.outName = v.getName();
                    task.outFile = v;
                    task.error = "mp3_converter_unavailable";
                } else {
                    copy(src, target);
                    task.error = "mp3_converter_unavailable";
                }
            } else if ("m4a".equals(task.kind)) {
                task.state = DownloadTask.State.CONVERTING;
                notifyChanged();
                File src = task.parts.get(0).temp;
                if (!NativeStreams.dashToMpeg(src, target)) {
                    copy(src, target);
                }
            } else {
                copy(task.parts.get(0).temp, target);
            }

            for (DownloadTask.Part p : task.parts) {
                if (p.temp != null && p.temp.exists()) {
                    p.temp.delete();
                }
            }
            task.completedAt = System.currentTimeMillis();
            task.state = DownloadTask.State.COMPLETED;
            Notifier.done(ctx, nid, task.title,
                    task.outFile == null ? null : task.outFile.getAbsolutePath());
        } catch (Http.Cancelled cancelled) {
            task.state = DownloadTask.State.PAUSED;
            Notifier.clearProgress(ctx, Notifier.idFor(task.id));
        } catch (Throwable t) {
            Log.w(TAG, "download failed", t);
            task.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            task.state = DownloadTask.State.FAILED;
            Notifier.clearProgress(ctx, Notifier.idFor(task.id));
        }
        persist();
        notifyChanged();
    }

    public void pause(DownloadTask task) {
        task.cancelToken.cancelled = true;
    }

    public void resume(DownloadTask task) {
        task.cancelToken = new Http.CancelToken();
        task.state = DownloadTask.State.QUEUED;
        notifyChanged();
        pool.execute(worker(task));
    }

    public void remove(DownloadTask task) {
        task.cancelToken.cancelled = true;
        tasks.remove(task);
        try {
            if (task.outFile != null && task.outFile.exists()) {
                task.outFile.delete();
            }
        } catch (Exception ignored) {
        }
        persist();
        notifyChanged();
    }

    public void pauseAll() {
        for (DownloadTask t : tasks) {
            if (t.isActive()) {
                pause(t);
            }
        }
    }

    public void resumeAll() {
        for (DownloadTask t : tasks) {
            if (t.state == DownloadTask.State.PAUSED) {
                resume(t);
            }
        }
    }

    /** True when the first part points at a video container (used to avoid fake .mp3 files). */
    private static boolean looksLikeVideo(DownloadTask t) {
        String u = (t.parts.isEmpty() || t.parts.get(0).url == null) ? "" : t.parts.get(0).url.toLowerCase();
        return u.contains(".mp4") || u.contains(".webm") || u.contains(".mkv")
                || u.contains(".mov") || u.contains("videoplayback");
    }

    private static String stripExt(String name) {
        if (name == null || name.isEmpty()) {
            return "SnapSave";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Never overwrite an existing file: "clip.mp4" -> "clip (2).mp4". */
    private static File unique(File f) {
        if (f == null || !f.exists()) {
            return f;
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) {
                return c;
            }
        }
        return f;
    }

    private void copy(File src, File dst) throws Exception {
        FileOutputStream fos = new FileOutputStream(dst);
        java.io.FileInputStream fis = new java.io.FileInputStream(src);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        } finally {
            fis.close();
            fos.flush();
            fos.close();
        }
    }

    // ---------------- persistence ----------------

    private File store() {
        return new File(dir, "library.json");
    }

    public synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (DownloadTask t : tasks) {
                if (t.state != DownloadTask.State.COMPLETED) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", t.title);
                o.put("author", t.author);
                o.put("grad", t.grad);
                o.put("kind", t.kind);
                o.put("label", t.label);
                o.put("outName", t.outName);
                o.put("completedAt", t.completedAt);
                o.put("size", t.outFile != null && t.outFile.exists() ? t.outFile.length() : 0);
                arr.put(o);
            }
            FileOutputStream fos = new FileOutputStream(store());
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "persist failed", e);
        }
    }

    public synchronized void loadPersisted() {
        try {
            File f = store();
            if (!f.exists()) {
                return;
            }
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int read = fis.read(buf);
            fis.close();
            JSONArray arr = new JSONArray(new String(buf, 0, read, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                DownloadTask t = new DownloadTask();
                t.id = o.optString("id");
                String stored = Resolvers.firstText(o.optString("title", null));
                t.title = stored != null ? stored : stripExt(t.outName);
                t.author = o.optString("author");
                t.grad = o.optInt("grad");
                t.kind = o.optString("kind", "video");
                t.label = o.optString("label", "");
                t.outName = o.optString("outName");
                t.completedAt = o.optLong("completedAt");
                t.outFile = new File(dir, t.outName);
                t.state = DownloadTask.State.COMPLETED;
                tasks.add(t);
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed", e);
        }
    }
}
