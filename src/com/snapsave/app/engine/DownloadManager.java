package com.snapsave.app.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import com.snapsave.app.util.Prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    /**
     * Worker threads are cheap; the actual transfer limit is enforced by
     * {@link #activeWorkers}. Users can raise or lower it in Settings without
     * rebuilding the executor.
     */
    private static final int MAX_WORKERS = 8;
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKERS);
    /** Number of workers currently allowed to transfer, guarded by this manager's monitor. */
    private int activeWorkers;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** State changes and file publication use this manager's monitor; native calls never hold it. */
    /** Kept for the lifetime of this application-scoped manager. */
    private final BroadcastReceiver networkReceiver;

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
        networkReceiver = NetworkPolicy.listen(this.ctx, new Runnable() {
            @Override public void run() { onNetworkChanged(); }
        });
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

    /** Add a prepared task; service/network policy must allow it before a worker is queued. */
    public synchronized void submit(DownloadTask t) {
        if (t == null || tasks.contains(t) || t.removed) return;
        if (t.id == null) {
            t.id = Long.toHexString(System.currentTimeMillis()) + "_" + (int) (Math.random() * 0xFFFF);
        }
        prepareFiles(t);
        tasks.add(0, t);
        t.state = DownloadTask.State.PAUSED;
        schedule(t);
    }

    private void prepareFiles(DownloadTask task) {
        if (task.outName == null || task.outName.isEmpty()) {
            task.outName = stripExt(task.title) + ("mp3".equals(task.kind) ? ".mp3"
                    : "m4a".equals(task.kind) ? ".m4a" : ".mp4");
        }
        if (task.outFile == null) {
            synchronized (NAME_LOCK) {
                File f = unique(new File(dir, task.outName));
                try { f.createNewFile(); } catch (IOException ignored) { }
                task.outFile = f;
                task.outName = f.getName();
            }
        }
        for (int i = 0; i < task.parts.size(); i++) {
            if (task.parts.get(i).temp == null) {
                task.parts.get(i).temp = new File(dir, task.id + "_part" + i + ".tmp");
            }
        }
    }

    /** Called only under the manager monitor. Never replace a live worker's token. */
    private void schedule(final DownloadTask task) {
        if (task.removed || task.state == DownloadTask.State.COMPLETED) return;
        if (!NetworkPolicy.allowed(ctx)) {
            waitForNetwork(task);
        } else if (!DownloadService.ensure(ctx)) {
            if (task.cancelToken != null) task.cancelToken.cancelled = true;
            task.restartRequested = false;
            task.waitingForNetwork = false;
            task.state = DownloadTask.State.PAUSED;
            task.error = "background_start_blocked";
        } else if (task.workerScheduled) {
            // The old download/native operation owns its token until its finally block runs.
            task.restartRequested = true;
            task.waitingForNetwork = false;
            task.state = DownloadTask.State.QUEUED;
            task.error = null;
        } else {
            task.cancelToken = new Http.CancelToken();
            final Http.CancelToken token = task.cancelToken;
            task.restartRequested = false;
            task.waitingForNetwork = false;
            task.workerScheduled = true;
            task.error = null;
            task.speed = 0;
            task.state = DownloadTask.State.QUEUED;
            try {
                pool.execute(new Runnable() {
                    @Override public void run() { runTask(task, token); }
                });
            } catch (RuntimeException e) {
                task.workerScheduled = false;
                token.cancelled = true;
                task.state = DownloadTask.State.PAUSED;
                task.error = "worker_start_failed";
                Log.w(TAG, "worker scheduling failed", e);
            }
        }
        persist();
        notifyChanged();
        DownloadService.refresh(ctx);
    }

    private void waitForNetwork(DownloadTask task) {
        if (task.cancelToken != null) task.cancelToken.cancelled = true;
        task.restartRequested = false;
        task.waitingForNetwork = true;
        task.state = DownloadTask.State.WAITING_NETWORK;
        task.speed = 0;
        Notifier.clearProgress(ctx, Notifier.idFor(task.id));
    }

    /** WAITING_NETWORK is active in the UI but must not hold a foreground service open. */
    public synchronized boolean hasRunnableTasks() {
        for (DownloadTask task : tasks) {
            if (!task.removed && task.state != DownloadTask.State.WAITING_NETWORK
                    && (task.isActive() || task.workerScheduled)) return true;
        }
        return false;
    }

    /** Connectivity callbacks may be delivered on the main thread. Never wait for workers here. */
    public synchronized void onNetworkChanged() {
        boolean allowed = NetworkPolicy.allowed(ctx);
        boolean changed = false;
        for (DownloadTask task : tasks) {
            if (task.removed) continue;
            if (!allowed && task.isActive() && task.state != DownloadTask.State.WAITING_NETWORK) {
                waitForNetwork(task);
                changed = true;
            } else if (allowed && task.waitingForNetwork
                    && task.state == DownloadTask.State.WAITING_NETWORK) {
                schedule(task);
                changed = true;
            }
        }
        if (changed) {
            persist();
            notifyChanged();
            DownloadService.refresh(ctx);
        }
    }

    private synchronized void checkCancelled(DownloadTask task, Http.CancelToken token)
            throws Http.Cancelled {
        if (task.removed || token.cancelled || task.cancelToken != token) throw new Http.Cancelled();
        if (!NetworkPolicy.allowed(ctx)) {
            waitForNetwork(task);
            throw new Http.Cancelled();
        }
    }

    private synchronized void transition(DownloadTask task, Http.CancelToken token,
                                         DownloadTask.State state) throws Http.Cancelled {
        checkCancelled(task, token);
        task.state = state;
        task.error = null;
        persist();
        notifyChanged();
    }

    /**
     * Blocks a worker until the user-selected number of simultaneous transfers allows it.
     * Wi-Fi and mobile data use the same limit; the network policy decides whether data is
     * allowed at all. Waiting threads are woken when a transfer finishes or the setting changes.
     */
    private void acquireWorkerSlot(DownloadTask task, Http.CancelToken token) throws Http.Cancelled {
        synchronized (this) {
            while (!task.removed && !token.stopped()
                    && activeWorkers >= Prefs.simultaneousDownloads()) {
                try {
                    wait(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Http.Cancelled();
                }
            }
            if (task.removed || token.stopped()) {
                throw new Http.Cancelled();
            }
            activeWorkers++;
        }
    }

    private void runTask(final DownloadTask task, final Http.CancelToken token) {
        final int nid = Notifier.idFor(task.id);
        File staging = stagingFile(task);
        boolean slotAcquired = false;
        try {
            acquireWorkerSlot(task, token);
            slotAcquired = true;
            transition(task, token, DownloadTask.State.DOWNLOADING);
            Notifier.progress(ctx, nid, task.title, task.percent());
            boolean refreshed = false;
            if (missingUrls(task)) {
                refreshed = true;
                if (!refreshTask(task, token)) throw new IOException("link_expired");
            }
            // A refreshed URL can reset earlier parts, so restart this loop, not runTask itself.
            while (true) {
                try {
                    downloadParts(task, token, nid);
                    break;
                } catch (Exception failure) {
                    checkCancelled(task, token);
                    if (refreshed || !expiredStatus(failure) || !hasSourcePage(task)) throw failure;
                    refreshed = true;
                    if (!refreshTask(task, token)) throw new IOException("link_expired", failure);
                }
            }
            checkCancelled(task, token);
            finalizeOutput(task, token, staging);
            checkCancelled(task, token);
            validateOutput(task, staging);
            synchronized (this) {
                checkCancelled(task, token);
                // The public name is only touched after output validation. Removal and publish
                // share the monitor, so a removed task can never publish a file afterwards.
                if (!staging.renameTo(task.outFile)) {
                    // Some platforms refuse replacing even the empty name reservation.
                    if (task.outFile.exists() && task.outFile.length() == 0) task.outFile.delete();
                    if (!staging.renameTo(task.outFile)) throw new IOException("output_publish_failed");
                }
                checkCancelled(task, token);
                task.completedAt = System.currentTimeMillis();
                task.state = DownloadTask.State.COMPLETED;
                task.speed = 0;
                task.error = null;
                for (DownloadTask.Part part : task.parts) {
                    Http.deletePartial(part.temp);
                }
                Notifier.done(ctx, nid, task.title, task.outFile.getName(), task.warning);
            }
        } catch (Throwable failure) {
            synchronized (this) {
                if (!task.removed) {
                    if (failure instanceof Http.Cancelled || token.cancelled) {
                        task.state = task.waitingForNetwork ? DownloadTask.State.WAITING_NETWORK
                                : task.restartRequested ? DownloadTask.State.QUEUED : DownloadTask.State.PAUSED;
                    } else if (!NetworkPolicy.allowed(ctx)) {
                        waitForNetwork(task);
                    } else {
                        Log.w(TAG, "download failed", failure);
                        task.error = failure.getMessage() == null
                                ? failure.getClass().getSimpleName() : failure.getMessage();
                        task.state = DownloadTask.State.FAILED;
                        if (task.outFile != null && task.outFile.length() == 0) task.outFile.delete();
                    }
                    task.speed = 0;
                    Notifier.clearProgress(ctx, nid);
                }
            }
        } finally {
            synchronized (this) {
                if (slotAcquired) {
                    activeWorkers = Math.max(0, activeWorkers - 1);
                    notifyAll();          // wake a queued worker waiting for a transfer slot
                }
                // Native processing has returned before this ownership flag can be released.
                staging.delete();
                task.workerScheduled = false;
                if (task.removed) {
                    deleteFiles(task);
                } else {
                    boolean restart = task.restartRequested;
                    task.restartRequested = false;
                    if (restart && task.state != DownloadTask.State.COMPLETED) schedule(task);
                    persist();
                }
                notifyChanged();
                DownloadService.refresh(ctx);
            }
        }
    }

    private void downloadParts(final DownloadTask task, final Http.CancelToken token, final int nid)
            throws Exception {
        final long[] win = {System.currentTimeMillis(), task.done()};
        final long[] lastUi = {0L};
        final int count = task.parts.size();
        final Http.CancelToken[] partTokens = new Http.CancelToken[count];
        final int budget = Math.max(1, Prefs.downloadConnections());
        int runnable = 0;
        for (int i = 0; i < count; i++) {
            checkCancelled(task, token);
            DownloadTask.Part part = task.parts.get(i);
            if (part.url == null || part.url.isEmpty()) throw new IOException("link_expired");
            if (part.temp == null) part.temp = new File(dir, task.id + "_part" + i + ".tmp");
            if (part.total > 0 && part.temp.exists() && part.temp.length() == part.total) {
                part.done = part.total;
                continue;
            }
            // Segment files count as progress too; otherwise a resumed file would show 0%.
            part.done = Http.partialBytes(part.temp);
            partTokens[i] = new Http.CancelToken();
            partTokens[i].parent = token;
            runnable++;
        }
        if (runnable == 0) {
            return;
        }
        final int perPart = runnable > 1 ? Math.max(1, budget / runnable) : budget;
        if (runnable == 1) {
            for (int i = 0; i < count; i++) {
                if (partTokens[i] != null) {
                    downloadPart(task, i, partTokens[i], token, nid, win, lastUi, perPart);
                }
            }
            return;
        }
        // Video-only and audio-only streams are independent. Fetch both at the same time
        // instead of serialising two large transfers.
        final Throwable[] failures = new Throwable[count];
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < count; i++) {
            if (partTokens[i] == null) {
                continue;
            }
            final int index = i;
            final Http.CancelToken partToken = partTokens[i];
            Thread thread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        downloadPart(task, index, partToken, token, nid, win, lastUi, perPart);
                    } catch (Throwable failure) {
                        failures[index] = failure;
                        // Stop sibling streams so a failed part does not keep burning data.
                        for (Http.CancelToken sibling : partTokens) {
                            if (sibling != null) sibling.cancelled = true;
                        }
                    }
                }
            }, "snapsave-part-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        for (int i = 0; i < threads.size(); i++) {
            try {
                threads.get(i).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new Http.Cancelled();
            }
        }
        checkCancelled(task, token);
        Throwable primary = null;
        for (int i = 0; i < count; i++) {
            if (failures[i] != null && !(failures[i] instanceof Http.Cancelled)) {
                primary = failures[i];
                break;
            }
        }
        if (primary == null) {
            for (int i = 0; i < count; i++) {
                if (failures[i] != null) {
                    primary = failures[i];
                    break;
                }
            }
        }
        if (primary != null) {
            if (primary instanceof Exception) throw (Exception) primary;
            if (primary instanceof Error) throw (Error) primary;
            throw new IOException(primary);
        }
    }

    private void downloadPart(final DownloadTask task, final int index,
                              final Http.CancelToken partToken, final Http.CancelToken taskToken,
                              final int nid, final long[] win, final long[] lastUi,
                              int connections) throws Exception {
        final DownloadTask.Part part = task.parts.get(index);
        Http.download(part.url, part.temp, part.referer, part.ua == null ? Http.UA : part.ua,
                new Http.Progress() {
            @Override public void onProgress(long done, long total) {
                synchronized (DownloadManager.this) {
                    if (task.removed || taskToken.stopped() || partToken.stopped()) return;
                    part.done = done;
                    if (total > 0) part.total = total;
                    long now = System.currentTimeMillis();
                    long dt = now - win[0];
                    if (dt >= 1000) {
                        task.speed = Math.max(0, task.done() - win[1]) * 1000.0 / dt;
                        win[0] = now;
                        win[1] = task.done();
                        Notifier.progress(ctx, nid, task.title, task.percent());
                        persistThrottled();
                    }
                    if (now - lastUi[0] >= 400) {
                        lastUi[0] = now;
                        notifyChanged();
                    }
                }
            }
        }, partToken, connections);
        synchronized (this) {
            checkCancelled(task, taskToken);
            part.done = part.temp.length();
            if (part.total <= 0) part.total = part.done;
            persist();
        }
    }

    private static boolean hasSourcePage(DownloadTask task) {
        return task.sourcePage != null && !task.sourcePage.isEmpty();
    }

    private static boolean missingUrls(DownloadTask task) {
        if (task.parts.isEmpty()) return true;
        for (DownloadTask.Part part : task.parts) {
            if (part.url == null || part.url.isEmpty()) return true;
        }
        return false;
    }

    private boolean refreshTask(DownloadTask task, Http.CancelToken token) throws Exception {
        checkCancelled(task, token);
        if (!hasSourcePage(task)) return false;
        // Old records may have no parts. Reconstruct the original selected topology before
        // asking TaskRefresh to resolve it, without inventing a different quality selection.
        synchronized (this) {
            checkCancelled(task, token);
            if (task.parts.isEmpty()) {
                task.parts.add(new DownloadTask.Part());
                if ("video".equals(task.kind) && !task.selectedProgressive
                        && task.formatToken == null && task.formatItag > 0) {
                    task.parts.add(new DownloadTask.Part());
                }
                prepareFiles(task);
            }
        }
        boolean ok = TaskRefresh.refresh(task);
        synchronized (this) {
            checkCancelled(task, token);
            if (ok) {
                prepareFiles(task);
                persist();
                notifyChanged();
            }
        }
        return ok && !missingUrls(task);
    }

    /** Typed HTTP status when present; retains compatibility with older IOException messages. */
    private static boolean expiredStatus(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof Http.HttpException) {
                int code = ((Http.HttpException) cause).status;
                if (code == 401 || code == 403 || code == 410) return true;
            }
            String message = cause.getMessage();
            if (message != null && message.matches("(?s).*\\bHTTP\\s+(401|403|410)\\b.*")) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private File stagingFile(DownloadTask task) {
        String name = task.outName == null ? "" : task.outName;
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot) : ".tmp";
        // FFmpeg/native helpers may infer the container from the output extension.
        return new File(dir, task.id + "_processing" + extension);
    }

    private void finalizeOutput(DownloadTask task, Http.CancelToken token, File target) throws Exception {
        checkCancelled(task, token);
        if (target.exists() && !target.delete()) throw new IOException("output_reset_failed");
        if ("video".equals(task.kind) && task.parts.size() >= 2) {
            transition(task, token, DownloadTask.State.MERGING);
            boolean ok;
            try {
                ok = NativeStreams.canMux()
                        && NativeStreams.mux(task.parts.get(1).temp, task.parts.get(0).temp, target);
            } catch (Throwable failure) {
                checkCancelled(task, token);
                throw new IOException("merge_failed", failure);
            }
            checkCancelled(task, token);
            if (!ok) throw new IOException("merge_failed");
        } else if ("mp3".equals(task.kind)) {
            transition(task, token, DownloadTask.State.CONVERTING);
            File src = task.parts.get(0).temp;
            if (isMp3Source(task) && hasMp3Frames(src)) {
                copy(task, token, src, target);
            } else {
                boolean ok;
                try {
                    ok = NativeStreams.canMp3() && NativeStreams.toMp3(src, target, task.mp3Bitrate);
                } catch (Throwable failure) {
                    checkCancelled(task, token);
                    throw new IOException("mp3_conversion_failed", failure);
                }
                checkCancelled(task, token);
                if (!ok) throw new IOException("mp3_conversion_failed");
            }
        } else if ("m4a".equals(task.kind)) {
            transition(task, token, DownloadTask.State.CONVERTING);
            File src = task.parts.get(0).temp;
            // A valid existing audio container does not require a native transformation.
            if (hasTracks(src, false, true, true)) {
                copy(task, token, src, target);
            } else {
                boolean ok = NativeStreams.canDashM4a() && NativeStreams.dashToMpeg(src, target);
                checkCancelled(task, token);
                if (!ok) throw new IOException("audio_conversion_failed");
            }
        } else {
            copy(task, token, task.parts.get(0).temp, target);
        }
        checkCancelled(task, token);
    }

    private static boolean hasTracks(File file, boolean video, boolean audio, boolean audioOnly) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            boolean hasVideo = false;
            boolean hasAudio = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) hasVideo = true;
                if (mime != null && mime.startsWith("audio/")) hasAudio = true;
            }
            return (!video || hasVideo) && (!audio || hasAudio) && (!audioOnly || !hasVideo);
        } catch (Exception ignored) {
            return false;
        } finally {
            extractor.release();
        }
    }

    private static void validateOutput(DownloadTask task, File file) throws IOException {
        if (!file.exists() || file.length() == 0) throw new IOException("empty_output");
        if ("mp3".equals(task.kind)) {
            if (!hasMp3Frames(file)) throw new IOException("mp3_conversion_failed");
        } else if ("video".equals(task.kind)) {
            boolean audioExpected = task.parts.size() >= 2 || task.selectedProgressive;
            if (!hasTracks(file, true, audioExpected, false)) {
                throw new IOException(task.parts.size() >= 2 ? "merge_failed" : "invalid_video_output");
            }
            if (!audioExpected && !hasTracks(file, false, true, false)) {
                task.warning = "video_only_no_audio";
            } else if ("video_only_no_audio".equals(task.warning)) {
                task.warning = null;
            }
        } else if ("m4a".equals(task.kind) && !hasTracks(file, false, true, true)) {
            throw new IOException("audio_conversion_failed");
        }
        // kind=file includes images and formats MediaExtractor does not support on all devices.
    }

    /** Skip an optional ID3v2 tag and require an MPEG layer III frame header. */
    private static boolean hasMp3Frames(File file) {
        try {
            java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r");
            try {
                if (in.length() < 4) return false;
                long offset = 0;
                if (in.length() >= 10 && in.read() == 'I' && in.read() == 'D' && in.read() == '3') {
                    in.seek(6);
                    int a = in.read(), b = in.read(), c = in.read(), d = in.read();
                    if (((a | b | c | d) & 0x80) != 0) return false;
                    offset = 10L + ((long) a << 21) + ((long) b << 14) + ((long) c << 7) + d;
                }
                long end = Math.min(in.length() - 4, offset + 4096);
                for (long pos = offset; pos <= end; pos++) {
                    in.seek(pos);
                    int a = in.read(), b = in.read(), c = in.read();
                    if (a == 0xff && (b & 0xe0) == 0xe0 && (b & 0x18) != 0x08
                            && (b & 0x06) == 0x02 && (c & 0xf0) != 0 && (c & 0xf0) != 0xf0
                            && (c & 0x0c) != 0x0c) return true;
                }
                return false;
            } finally { in.close(); }
        } catch (IOException ignored) { return false; }
    }

    /** True when the selected stream is already an MP3 file (no transcode needed). */
    private static boolean isMp3Source(DownloadTask t) {
        String u = (t.parts.isEmpty() || t.parts.get(0).url == null) ? "" : t.parts.get(0).url.toLowerCase(java.util.Locale.US);
        return "audio/mpeg".equals(t.sourceMime) || u.contains(".mp3")
                || u.contains("mime=audio%2fmpeg") || u.contains("audio/mpeg");
    }

    public synchronized void pause(DownloadTask task) {
        if (task == null || task.removed || task.state == DownloadTask.State.COMPLETED) return;
        if (task.cancelToken != null) task.cancelToken.cancelled = true;
        task.restartRequested = false;
        task.waitingForNetwork = false;
        task.recoveredActive = false;
        recovered.remove(task);
        if (task.isActive() || task.workerScheduled) task.state = DownloadTask.State.PAUSED;
        task.speed = 0;
        Notifier.clearProgress(ctx, Notifier.idFor(task.id));
        persist();
        notifyChanged();
        DownloadService.refresh(ctx);
    }

    public synchronized void resume(DownloadTask task) {
        if (task == null || task.removed || !tasks.contains(task)
                || task.state == DownloadTask.State.COMPLETED) return;
        if (task.isActive() && task.state != DownloadTask.State.WAITING_NETWORK) return;
        if (missingUrls(task) && !hasSourcePage(task)) {
            task.error = "link_expired";
            task.state = DownloadTask.State.FAILED;
            persist();
            notifyChanged();
            return;
        }
        prepareFiles(task);
        schedule(task);
    }

    public synchronized void remove(DownloadTask task) {
        if (task == null || task.removed) return;
        task.removed = true;
        task.restartRequested = false;
        task.waitingForNetwork = false;
        task.recoveredActive = false;
        if (task.cancelToken != null) task.cancelToken.cancelled = true;
        tasks.remove(task);
        recovered.remove(task);
        // A native operation cannot be stopped. Its private staging output and source files
        // stay owned by the worker until it returns; it cannot publish after removed is set.
        if (task.outFile != null) task.outFile.delete();
        if (!task.workerScheduled) deleteFiles(task);
        Notifier.clearProgress(ctx, Notifier.idFor(task.id));
        persist();
        notifyChanged();
        DownloadService.refresh(ctx);
    }

    /** Renames a completed file inside the app folder and updates the persisted record. */
    public synchronized boolean rename(DownloadTask task, String newName) {
        if (task == null || task.outFile == null || !task.outFile.exists()) {
            return false;
        }
        String oldName = task.outFile.getName();
        int dot = oldName.lastIndexOf('.');
        String extension = dot > 0 ? oldName.substring(dot) : "";
        String cleaned = newName == null ? "" : newName.trim()
                .replace('/', '_').replace('\\', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_');
        if (cleaned.isEmpty()) {
            return false;
        }
        if (!extension.isEmpty()
                && !cleaned.toLowerCase(java.util.Locale.US)
                .endsWith(extension.toLowerCase(java.util.Locale.US))) {
            cleaned += extension;
        }
        if (cleaned.equals(oldName)) {
            return true;
        }
        File destination = unique(new File(dir, cleaned));
        if (!task.outFile.renameTo(destination)) {
            return false;
        }
        task.outFile = destination;
        task.outName = destination.getName();
        persist();
        notifyChanged();
        return true;
    }

    private void deleteFiles(DownloadTask task) {
        if (task.outFile != null) task.outFile.delete();
        stagingFile(task).delete();
        for (DownloadTask.Part part : task.parts) {
            Http.deletePartial(part.temp);
        }
    }

    /** Wake workers waiting for a transfer slot after the user changes the limit. */
    public synchronized void wakeQueue() {
        notifyAll();
    }

    /** Removes completed records and their files (Settings -> Stored on this device). */
    public synchronized void deleteAllCompleted() {
        List<DownloadTask> finished = new ArrayList<DownloadTask>();
        for (DownloadTask task : tasks) {
            if (task.state == DownloadTask.State.COMPLETED) {
                finished.add(task);
            }
        }
        for (DownloadTask task : finished) {
            tasks.remove(task);
            deleteFiles(task);
        }
        if (!finished.isEmpty()) {
            persist();
            notifyChanged();
            DownloadService.refresh(ctx);
        }
    }

    public synchronized void pauseAll() {
        for (DownloadTask task : tasks) {
            if (task.isActive() || task.workerScheduled) pause(task);
        }
    }

    /** Explicit user action; automatic reconnect only resumes WAITING_NETWORK in onNetworkChanged. */
    public synchronized void resumeAll() {
        for (DownloadTask task : tasks) {
            if (task.state == DownloadTask.State.PAUSED
                    || task.state == DownloadTask.State.WAITING_NETWORK) resume(task);
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

    private void copy(DownloadTask task, Http.CancelToken token, File src, File dst) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = null;
        try {
            synchronized (this) {
                checkCancelled(task, token);
                fos = new FileOutputStream(dst);
            }
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) {
                synchronized (this) {
                    checkCancelled(task, token);
                    fos.write(buf, 0, n);
                }
            }
            synchronized (this) {
                checkCancelled(task, token);
                fos.flush();
            }
        } finally {
            try { fis.close(); } finally { if (fos != null) fos.close(); }
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
        o.put("schemaVersion", 2);
        o.put("id", t.id);
        o.put("mediaType", t.mediaType);
        o.put("mimeType", t.mimeType);
        o.put("warning", t.warning);
        o.put("sourcePage", t.sourcePage);
        o.put("provider", t.provider);
        o.put("formatToken", t.formatToken);
        o.put("sourceMime", t.sourceMime);
        o.put("formatItag", t.formatItag);
        o.put("formatHeight", t.formatHeight);
        o.put("formatBitrate", t.formatBitrate);
        o.put("selectedAudioOnly", t.selectedAudioOnly);
        o.put("selectedProgressive", t.selectedProgressive);
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
        AtomicFile atomic = new AtomicFile(dst);
        FileOutputStream fos = null;
        try {
            fos = atomic.startWrite();
            fos.write(body.getBytes("UTF-8"));
            atomic.finishWrite(fos);
        } catch (Exception e) {
            if (fos != null) atomic.failWrite(fos);
            Log.w(TAG, "persist write failed", e);
        }
    }

    public synchronized void loadPersisted() {
        try {
            AtomicFile atomic = new AtomicFile(store());
            if (!store().exists() && !new File(store().getPath() + ".bak").exists()) return;
            String raw = readFile(atomic.openRead());
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
                t.mediaType = nullable(o, "mediaType");
                t.mimeType = nullable(o, "mimeType");
                t.warning = nullable(o, "warning");
                t.sourcePage = nullable(o, "sourcePage");
                t.provider = nullable(o, "provider");
                t.formatToken = nullable(o, "formatToken");
                t.sourceMime = nullable(o, "sourceMime");
                t.formatItag = o.optInt("formatItag", 0);
                t.formatHeight = o.optInt("formatHeight", 0);
                t.formatBitrate = o.optInt("formatBitrate", 0);
                t.selectedAudioOnly = o.optBoolean("selectedAudioOnly",
                        "mp3".equals(t.kind) || "m4a".equals(t.kind));
                t.selectedProgressive = o.optBoolean("selectedProgressive", false);
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
                } else if ("WAITING_NETWORK".equals(state)) {
                    t.state = DownloadTask.State.WAITING_NETWORK;
                    t.waitingForNetwork = true;
                } else {
                    // Interrupted mid-flight (process death while QUEUED/DOWNLOADING/MERGING):
                    // keep it resumable, and remember that the user never asked to pause it.
                    t.state = DownloadTask.State.PAUSED;
                    t.recoveredActive = true;
                }
                if (findById(t.id) == null) tasks.add(t);
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
    public synchronized void resumeRecovered() {
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
            long actual = Http.partialBytes(p.temp);
            p.done = actual;
            t.parts.add(p);
        }
    }

    private static String nullable(JSONObject object, String key) {
        String value = object.isNull(key) ? null : object.optString(key, null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String readFile(InputStream fis) throws Exception {
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
