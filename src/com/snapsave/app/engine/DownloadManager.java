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

    /** Serialises name reservation so two same-titled submissions cannot pick one path. */
    private static final Object NAME_LOCK = new Object();

    /** Add a prepared task and start it. */
    public void submit(DownloadTask t) {
        if (t.id == null) {
            t.id = Long.toHexString(System.currentTimeMillis()) + "_" + (int) (Math.random() * 0xFFFF);
        }
        if (t.outFile == null) {
            synchronized (NAME_LOCK) {
                File f = unique(new File(dir, t.outName));
                try {
                    f.createNewFile();       // reserve the name for the lifetime of this task
                } catch (Exception ignored) {
                }
                t.outFile = f;
                t.outName = f.getName();
            }
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
            if (task.removed) {
                return;
            }
            // Checked before the DOWNLOADING transition: a task paused while it was still queued
            // must not flash back to running (and must never be persisted as auto-resumable).
            if (task.cancelToken.cancelled) {
                throw new Http.Cancelled();
            }
            task.state = DownloadTask.State.DOWNLOADING;
            task.error = null;
            persist();                    // record the state transition before doing any work
            notifyChanged();
            final int nid = Notifier.idFor(task.id);
            Notifier.progress(ctx, nid, task.title, 0);

            final long[] win = {System.currentTimeMillis(), 0L};
            final long[] lastUi = {0L};

            for (int index = 0; index < task.parts.size(); index++) {
                final DownloadTask.Part part = task.parts.get(index);
                if (task.cancelToken.cancelled) {
                    throw new Http.Cancelled();
                }
                if (part.temp == null) {
                    part.temp = new File(dir, task.id + "_part" + index + ".tmp");
                }
                // Never re-request a part that already finished: "Range: bytes=<size>-" makes
                // most servers answer 416 and would break a resume that only needs later parts.
                if (part.total > 0 && part.temp.exists() && part.temp.length() >= part.total) {
                    part.done = part.total;
                    notifyChanged();
                    continue;
                }
                if (part.temp.exists() && part.temp.length() > 0) {
                    part.done = part.temp.length();
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
                            persistThrottled();   // part totals/done survive a process kill
                        }
                        // The byte counter is updated on every tick, but rebuilding every row is
                        // not free: refresh the list at most a few times a second, not 7.
                        if (now - lastUi[0] >= 400) {
                            lastUi[0] = now;
                            notifyChanged();
                        }
                    }
                }, task.cancelToken);
                if (task.cancelToken.cancelled) {
                    throw new Http.Cancelled();
                }
                part.done = part.temp.length();
                if (part.total <= 0) {
                    part.total = part.done;
                }
                persist();                    // a finished part is never fetched again
            }

            if (task.cancelToken.cancelled) {
                throw new Http.Cancelled();
            }

            // ---- finalize with the native engine ----
            File target = task.outFile;
            if (target == null) {
                target = task.outFile = new File(dir, task.outName);
            }
            if ("video".equals(task.kind) && task.parts.size() >= 2) {
                task.state = DownloadTask.State.MERGING;
                persist();
                notifyChanged();
                File video = task.parts.get(0).temp;
                File audio = task.parts.get(1).temp;
                if (NativeStreams.canMux()) {
                    boolean ok = NativeStreams.mux(audio, video, target);
                    if (!ok) {
                        // mux failed: keep the video track so the user still gets a usable file
                        copy(video, target);
                        task.error = "merge_failed";
                    }
                } else {
                    copy(video, target);
                    task.error = "native_engine_unavailable";
                }
            } else if ("mp3".equals(task.kind)) {
                task.state = DownloadTask.State.CONVERTING;
                persist();
                notifyChanged();
                File src = task.parts.get(0).temp;
                if (isMp3Source(task)) {
                    copy(src, target);            // already MP3 — never re-encode and lose quality
                } else if (NativeStreams.canMp3() && NativeStreams.toMp3(src, target, task.mp3Bitrate)) {
                    // done by the native transcoder
                } else {
                    // Transcoding failed: keep the real container instead of shipping a non-MP3
                    // payload under a .mp3 name.
                    String ext = audioExtFor(task);
                    File alt = unique(new File(dir, stripExt(task.outName) + ext));
                    copy(src, alt);
                    task.outFile = alt;
                    task.outName = alt.getName();
                    task.error = "mp3_converter_unavailable";
                }
            } else if ("m4a".equals(task.kind)) {
                task.state = DownloadTask.State.CONVERTING;
                persist();
                notifyChanged();
                File src = task.parts.get(0).temp;
                if (!NativeStreams.canDashM4a() || !NativeStreams.dashToMpeg(src, target)) {
                    copy(src, target);
                }
            } else {
                copy(task.parts.get(0).temp, target);
            }

            if (task.outFile == null || !task.outFile.exists() || task.outFile.length() == 0) {
                throw new java.io.IOException("empty output");
            }

            for (DownloadTask.Part p : task.parts) {
                if (p.temp != null && p.temp.exists()) {
                    p.temp.delete();
                }
            }
            task.completedAt = System.currentTimeMillis();
            task.state = DownloadTask.State.COMPLETED;
            task.speed = 0;
            Notifier.done(ctx, nid, task.title,
                    task.outFile == null ? null : task.outFile.getName());
        } catch (Http.Cancelled cancelled) {
            task.state = DownloadTask.State.PAUSED;
            task.speed = 0;
            Notifier.clearProgress(ctx, Notifier.idFor(task.id));
        } catch (Throwable t) {
            if (task.removed) {
                return;                   // removal already cleaned up; never resurrect the row
            }
            Log.w(TAG, "download failed", t);
            task.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            task.state = DownloadTask.State.FAILED;
            task.speed = 0;
            Notifier.clearProgress(ctx, Notifier.idFor(task.id));
            // Drop the reserved-but-empty placeholder so it cannot look like a finished file.
            // Partial part files are kept: they are what makes "Retry" resume instead of restart.
            try {
                if (task.outFile != null && task.outFile.exists() && task.outFile.length() == 0) {
                    task.outFile.delete();
                }
            } catch (Exception ignored) {
            }
        }
        if (!task.removed) {
            persist();
        }
        notifyChanged();
    }

    /** True when the selected stream is already an MP3 file (no transcode needed). */
    private static boolean isMp3Source(DownloadTask t) {
        String u = (t.parts.isEmpty() || t.parts.get(0).url == null) ? "" : t.parts.get(0).url.toLowerCase();
        return u.contains(".mp3") || u.contains("mime=audio%2Fmpeg") || u.contains("audio/mpeg");
    }

    /**
     * Real container of the source file, used when transcoding is unavailable.
     *
     * "Extract audio from video" points at a video container, so falling back to ".m4a" would save
     * an MP4 under an audio extension. Detect the actual container instead of guessing audio.
     */
    private static String audioExtFor(DownloadTask t) {
        String u = (t.parts.isEmpty() || t.parts.get(0).url == null) ? "" : t.parts.get(0).url.toLowerCase();
        if (t.sourceIsVideo) {
            // "Extract audio" points at a video container — never rename it to an audio extension.
            if (u.contains(".webm")) {
                return ".webm";
            }
            if (u.contains(".mkv")) {
                return ".mkv";
            }
            if (u.contains(".mov")) {
                return ".mov";
            }
            return ".mp4";
        }
        if (u.contains(".webm")) {
            return ".webm";                       // video container
        }
        if (u.contains(".mkv")) {
            return ".mkv";
        }
        if (u.contains(".mov")) {
            return ".mov";
        }
        if (u.contains(".mp4") || u.contains("mime=video")) {
            return ".mp4";                        // video container — never label it .m4a
        }
        for (String ext : new String[]{".m4a", ".aac", ".opus", ".flac", ".wav", ".ogg"}) {
            if (u.contains(ext)) {
                return ext;
            }
        }
        return ".m4a";
    }

    public void pause(DownloadTask task) {
        if (task == null || task.cancelToken == null) {
            return;
        }
        task.cancelToken.cancelled = true;
        // The worker flips to PAUSED and persists once it observes the token; record it now too so
        // a kill in that window cannot restore the task as "DOWNLOADING" and lose the intent.
        if (task.isActive()) {
            task.state = DownloadTask.State.PAUSED;
            persist();
            notifyChanged();
        }
    }

    public void resume(DownloadTask task) {
        if (task == null || task.isActive() || task.state == DownloadTask.State.COMPLETED) {
            return;   // never run the same task twice
        }
        // A recovered task may have lost its transient state during process death.
        if (task.cancelToken == null) {
            task.cancelToken = new Http.CancelToken();
        }
        if (task.parts.isEmpty() || task.parts.get(0).url == null
                || task.parts.get(0).url.isEmpty()) {
            // Signed URLs are gone (or were never recorded): the page must be resolved again.
            task.error = "link_expired";
            task.state = DownloadTask.State.FAILED;
            persist();
            notifyChanged();
            return;
        }
        for (int i = 0; i < task.parts.size(); i++) {
            DownloadTask.Part p = task.parts.get(i);
            if (p.temp == null) {
                p.temp = new File(dir, task.id + "_part" + i + ".tmp");
            }
        }
        if (task.outFile == null || task.outName == null || task.outName.isEmpty()) {
            task.outName = stripExt(task.title) + ".mp4";
            task.outFile = unique(new File(dir, task.outName));
            task.outName = task.outFile.getName();
        }
        task.error = null;
        task.speed = 0;
        task.removed = false;
        task.cancelToken.cancelled = false;
        task.state = DownloadTask.State.QUEUED;
        persist();
        notifyChanged();
        pool.execute(worker(task));
    }

    public void remove(DownloadTask task) {
        if (task == null) {
            return;
        }
        task.removed = true;
        if (task.cancelToken != null) {
            task.cancelToken.cancelled = true;
        }
        tasks.remove(task);
        // A running worker keeps its own File references, so deleting here is safe: it only ever
        // writes to temp files, and it bails out on `removed` before touching the output again.
        try {
            if (task.outFile != null && task.outFile.exists()) {
                task.outFile.delete();
            }
        } catch (Exception ignored) {
        }
        for (DownloadTask.Part p : task.parts) {
            try {
                if (p.temp != null && p.temp.exists()) {
                    p.temp.delete();
                }
            } catch (Exception ignored) {
            }
        }
        Notifier.clearProgress(ctx, Notifier.idFor(task.id));
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

    /** Progress ticks persist at most this often; state transitions always persist immediately. */
    private static final long PERSIST_INTERVAL_MS = 2000;
    private long lastPersistAt;

    /**
     * Persist from a progress callback without fsyncing the whole library on every tick.
     * A kill loses at most {@link #PERSIST_INTERVAL_MS} of byte counts (the part file length is
     * authoritative on reload anyway, so nothing is re-downloaded because of it).
     */
    private synchronized void persistThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastPersistAt < PERSIST_INTERVAL_MS) {
            return;
        }
        lastPersistAt = now;
        persist();
    }

    /**
     * Writes every task — running ones included — so a process kill does not lose queued or
     * partial downloads. The file is replaced atomically: a kill mid-write can no longer leave
     * a truncated library behind.
     */
    public synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (DownloadTask t : tasks) {
                if (t.removed) {
                    continue;             // already deleted from the list; never resurrect it
                }
                try {
                    arr.put(toJson(t));
                } catch (Exception ignored) {
                }
            }
            writeAtomic(store(), arr.toString());
        } catch (Exception e) {
            Log.w(TAG, "persist failed", e);
        }
    }

    private JSONObject toJson(DownloadTask t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", t.id);
        o.put("title", t.title);
        o.put("author", t.author);
        o.put("grad", t.grad);
        o.put("kind", t.kind);
        o.put("sourceIsVideo", t.sourceIsVideo);
        o.put("label", t.label);
        o.put("outName", t.outName);
        o.put("state", t.state.name());
        o.put("createdAt", t.createdAt);
        o.put("completedAt", t.completedAt);
        o.put("mp3Bitrate", t.mp3Bitrate);
        o.put("error", t.error == null ? "" : t.error);
        o.put("size", t.outFile != null && t.outFile.exists() ? t.outFile.length() : 0);
        JSONArray parts = new JSONArray();
        for (DownloadTask.Part p : t.parts) {
            JSONObject po = new JSONObject();
            po.put("url", p.url);
            po.put("referer", p.referer);
            po.put("ua", p.ua);
            po.put("temp", p.temp == null ? "" : p.temp.getAbsolutePath());
            po.put("total", p.total);
            po.put("done", p.done);
            parts.put(po);
        }
        o.put("parts", parts);
        return o;
    }

    private void writeAtomic(File dst, String body) {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(body.getBytes("UTF-8"));
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Exception ignored) {
            }
            fos.close();
            fos = null;
            if (!tmp.renameTo(dst)) {
                dst.delete();
                tmp.renameTo(dst);
            }
        } catch (Exception e) {
            Log.w(TAG, "persist write failed", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public synchronized void loadPersisted() {
        try {
            File f = store();
            if (!f.exists()) {
                return;
            }
            String raw = readFile(f);
            if (raw.trim().isEmpty()) {
                return;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                DownloadTask t = new DownloadTask();
                t.id = o.optString("id", null);
                if (t.id == null || t.id.isEmpty()) {
                    t.id = Long.toHexString(System.currentTimeMillis()) + "_" + i;
                }
                t.outName = o.optString("outName", null);
                String stored = Resolvers.firstText(o.optString("title", null));
                t.title = stored != null ? stored : stripExt(t.outName);
                t.author = o.optString("author", "");
                t.grad = o.optInt("grad");
                t.kind = o.optString("kind", "video");
                t.sourceIsVideo = o.optBoolean("sourceIsVideo", false);
                t.label = o.optString("label", "");
                t.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                t.completedAt = o.optLong("completedAt");
                t.mp3Bitrate = o.optInt("mp3Bitrate", 128);
                String err = o.optString("error", "");
                t.error = err.isEmpty() ? null : err;
                if (t.outName == null || t.outName.isEmpty()) {
                    t.outName = stripExt(t.title) + ".mp4";
                }
                restoreParts(t, o.optJSONArray("parts"));
                t.outFile = new File(dir, t.outName);

                String state = o.optString("state", "COMPLETED");
                if ("COMPLETED".equals(state)) {
                    // Prune records whose file was deleted outside the app.
                    if (!t.outFile.exists() || t.outFile.length() == 0) {
                        continue;
                    }
                    t.state = DownloadTask.State.COMPLETED;
                } else if ("FAILED".equals(state)) {
                    t.state = DownloadTask.State.FAILED;
                } else if ("PAUSED".equals(state)) {
                    t.state = DownloadTask.State.PAUSED;
                } else {
                    // Interrupted mid-flight (process death while QUEUED/DOWNLOADING/MERGING):
                    // keep it resumable, and remember that the user never asked to pause it.
                    t.state = DownloadTask.State.PAUSED;
                    t.recoveredActive = true;
                }
                tasks.add(t);
            }
            recovered.addAll(collectRecovered());
        } catch (Exception e) {
            Log.w(TAG, "load failed", e);
        }
    }

    /** Tasks that were mid-flight when the process died; resumed shortly after startup. */
    private final List<DownloadTask> recovered = new CopyOnWriteArrayList<DownloadTask>();

    private List<DownloadTask> collectRecovered() {
        List<DownloadTask> out = new ArrayList<DownloadTask>();
        for (DownloadTask t : tasks) {
            if (t.recoveredActive) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Resume downloads that were interrupted by process death. Called from {@link App} after
     * {@link #loadPersisted()}; the short delay lets the UI attach its listener first.
     */
    public void resumeRecovered() {
        List<DownloadTask> pending = new ArrayList<DownloadTask>(recovered);
        recovered.clear();
        for (DownloadTask t : pending) {
            t.recoveredActive = false;
            if (t.state == DownloadTask.State.PAUSED && !t.removed) {
                resume(t);
            }
        }
    }

    private void restoreParts(DownloadTask t, JSONArray parts) {
        if (parts == null) {
            return;
        }
        for (int i = 0; i < parts.length(); i++) {
            JSONObject po = parts.optJSONObject(i);
            if (po == null) {
                continue;
            }
            DownloadTask.Part p = new DownloadTask.Part();
            p.url = po.optString("url", null);
            if (p.url == null || p.url.isEmpty()) {
                continue;
            }
            String ref = po.optString("referer", null);
            p.referer = (ref == null || ref.isEmpty()) ? null : ref;
            String ua = po.optString("ua", null);
            p.ua = (ua == null || ua.isEmpty()) ? null : ua;
            p.total = po.optLong("total");
            p.done = po.optLong("done");
            String temp = po.optString("temp", "");
            p.temp = temp.isEmpty() ? new File(dir, t.id + "_part" + i + ".tmp") : new File(temp);
            // The file on disk is authoritative: a stale `done` would show a false percentage,
            // and a temp that vanished (cache wipe, user cleanup) must restart from zero.
            long actual = p.temp.exists() ? p.temp.length() : 0;
            p.done = actual;
            if (p.total > 0 && actual > p.total) {
                p.total = actual;         // grew past the recorded total: trust the bytes we have
            }
            t.parts.add(p);
        }
    }

    private static String readFile(File f) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            fis.close();
        }
    }
}
