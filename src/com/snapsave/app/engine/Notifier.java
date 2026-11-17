package com.snapsave.app.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import com.snapsave.app.R;
import com.snapsave.app.util.Util;

import java.util.HashMap;
import java.util.Map;

/** Stable per-task progress, status, and completion notifications. */
public final class Notifier {
    public static final String CHANNEL = "downloads_v2";
    private static final Map<Integer, Long> lastUpdates = new HashMap<Integer, Long>();
    private static final Map<Integer, DownloadTask.State> lastStates = new HashMap<Integer, DownloadTask.State>();
    private Notifier() { }

    public static int idFor(String taskId) {
        if (taskId == null || taskId.isEmpty()) return 100000;
        int h = 0x811c9dc5;
        for (int i = 0; i < taskId.length(); i++) { h ^= taskId.charAt(i) & 0xff; h *= 0x01000193; }
        h ^= h >>> 16; h *= 0x7feb352d; h ^= h >>> 15;
        return 100000 + ((h & 0x7fffffff) % 900000);
    }

    public static boolean enabled(Context c) {
        try {
            NotificationManager m = nm(c);
            if (Build.VERSION.SDK_INT >= 24 && m != null && !m.areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= 33) return c.getPackageManager().checkPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS, c.getPackageName())
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            return true;
        } catch (Throwable ignored) { return true; }
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = nm(c);
            if (m == null || m.getNotificationChannel(CHANNEL) != null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    c.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(c.getString(R.string.channel_downloads_desc));
            channel.setShowBadge(true); m.createNotificationChannel(channel);
        }
    }

    public static void progress(Context c, int id, DownloadTask task) {
        if (task == null || task.removed || task.state == DownloadTask.State.COMPLETED) return;
        try {
            NotificationManager m = nm(c);
            if (m == null || !enabled(c)) return;
            long now = SystemClock.elapsedRealtime();
            DownloadTask.State state = task.state;
            synchronized (lastUpdates) {
                Long previous = lastUpdates.get(id);
                if (lastStates.get(id) == state && previous != null
                        && (state == DownloadTask.State.PAUSED || state == DownloadTask.State.FAILED || now - previous < 750)) return;
                lastUpdates.put(id, now); lastStates.put(id, state);
            }
            ensureChannel(c);
            boolean processing = state == DownloadTask.State.MERGING || state == DownloadTask.State.CONVERTING;
            boolean saving = state == DownloadTask.State.PUBLISHING;
            boolean live = state == DownloadTask.State.DOWNLOADING || state == DownloadTask.State.QUEUED
                    || processing || saving || state == DownloadTask.State.WAITING_NETWORK;
            long done = saving ? task.publishDone : task.done();
            long total = saving ? task.publishTotal : task.total();
            boolean known = knownSize(task);
            String stateLabel = stateLabel(task);
            String bytes = Util.bytes(Math.max(0, done)) + (known ? " / " + Util.bytes(total) : " • size unknown");
            String details = stateLabel + (processing ? " • transfer complete" : " • " + (known ? percent(done, total) + "% • " : "") + bytes);
            if (state == DownloadTask.State.DOWNLOADING) {
                details += "\n" + Util.speed(Math.max(0, task.speed));
                if (known && task.speed > 0 && total > done) details += " • " + eta((long) ((total - done) / task.speed)) + " left";
            }
            if ((state == DownloadTask.State.FAILED || state == DownloadTask.State.PAUSED)
                    && task.error != null && !task.error.isEmpty()) details += "\n" + error(task.error);
            Notification.Builder b = builder(c).setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(task.title == null || task.title.isEmpty() ? "SnapSave download" : task.title)
                    .setContentText(details.replace('\n', ' '))
                    .setStyle(new Notification.BigTextStyle().bigText(details))
                    .setSubText("SnapSave • " + stateLabel)
                    .setContentIntent(openApp(c)).setOnlyAlertOnce(true).setOngoing(live)
                    .setAutoCancel(!live).setShowWhen(false);
            if (live) b.setProgress(100, percent(done, total), !known || processing);
            if (state == DownloadTask.State.DOWNLOADING || state == DownloadTask.State.QUEUED
                    || state == DownloadTask.State.WAITING_NETWORK) {
                b.addAction(R.drawable.ic_pause, "Pause", DownloadService.taskAction(c, DownloadService.PAUSE_TASK, task.id));
            } else if (state == DownloadTask.State.PAUSED || state == DownloadTask.State.FAILED) {
                // The Downloads screen owns the legacy storage permission gate on Retry.
                b.addAction(R.drawable.ic_play, state == DownloadTask.State.FAILED ? "Retry in Downloads" : "Resume in Downloads", openApp(c));
            }
            b.addAction(R.drawable.ic_close, "Cancel", DownloadService.taskAction(c, DownloadService.CANCEL_TASK, task.id));
            m.notify(id, b.build());
        } catch (Throwable ignored) { }
    }

    public static void status(Context c, DownloadTask task) {
        if (task != null) progress(c, idFor(task.id), task);
    }

    /** Compatibility for callers that have no task object: negative percent means unknown size. */
    public static void progress(Context c, int id, String title, int percent) {
        try {
            NotificationManager m = nm(c);
            if (m == null || !enabled(c)) return;
            ensureChannel(c);
            boolean known = percent >= 0;
            m.notify(id, builder(c).setContentTitle(title == null ? "Downloading" : title)
                    .setContentText(known ? percent + "%" : "Downloading • size unknown")
                    .setSmallIcon(R.drawable.ic_download).setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(openApp(c)).setProgress(100, Math.max(0, Math.min(100, percent)), !known).build());
        } catch (Throwable ignored) { }
    }

    public static void done(Context c, int id, String title, String path) { done(c, id, title, path, null); }
    public static void done(Context c, int id, String title, String path, String warning) {
        forget(id);
        try {
            NotificationManager m = nm(c);
            if (m == null || !enabled(c)) return;
            ensureChannel(c);
            boolean hasWarning = warning != null && !warning.isEmpty();
            String body = (title == null ? "Media" : title) + "\nSaved to " + MediaFiles.locationLabel();
            if (path != null && !path.isEmpty() && !path.startsWith("content:")) body += "\n" + path;
            if (hasWarning) body += "\n" + ("video_only_no_audio".equals(warning)
                    ? "Video saved without an audio track" : "Check the download details for a warning");
            m.notify(id, builder(c).setContentTitle(hasWarning ? "Download completed with warning" : c.getString(R.string.download_complete))
                    .setContentText(title == null ? "Saved to " + MediaFiles.locationLabel() : title)
                    .setStyle(new Notification.BigTextStyle().bigText(body)).setSmallIcon(R.drawable.ic_download)
                    .setAutoCancel(true).setContentIntent(openApp(c)).build());
        } catch (Throwable ignored) { }
    }

    public static void clearProgress(Context c, int id) {
        forget(id);
        try { NotificationManager m = nm(c); if (m != null) m.cancel(id); } catch (Throwable ignored) { }
    }
    private static void forget(int id) {
        synchronized (lastUpdates) { lastUpdates.remove(id); lastStates.remove(id); }
    }
    static boolean knownSize(DownloadTask t) {
        if (t.state == DownloadTask.State.PUBLISHING) return t.publishTotal > 0;
        if (t.parts.isEmpty()) return false;
        for (DownloadTask.Part p : t.parts) if (p.total <= 0) return false;
        return true;
    }
    static int percent(long done, long total) {
        return total <= 0 ? 0 : (int) Math.max(0, Math.min(100, done * 100.0 / total));
    }
    private static String stateLabel(DownloadTask t) {
        switch (t.state) {
            case DOWNLOADING: return "Downloading";
            case QUEUED: return "Queued";
            case WAITING_NETWORK: return "Waiting for network / Wi-Fi";
            case PAUSED: return "Paused";
            case FAILED: return "Failed";
            case MERGING: return "Combining video and audio";
            case CONVERTING: return "Converting audio";
            case PUBLISHING: return "Saving to " + MediaFiles.locationLabel();
            default: return "Download";
        }
    }
    private static String error(String error) {
        if (error.contains("storage_permission_required")) return "Storage permission needed • open Downloads to retry";
        if (error.contains("save_failed")) return "Could not save file • open Downloads to retry saving";
        return error;
    }
    private static String eta(long seconds) {
        if (seconds < 60) return Math.max(1, seconds) + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + (seconds % 3600) / 60 + "m";
    }
    private static NotificationManager nm(Context c) { return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); }
    private static Notification.Builder builder(Context c) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
    }
    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, com.snapsave.app.ui.MainActivity.class).putExtra("tab", 2);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 202, i, flags);
    }
}
