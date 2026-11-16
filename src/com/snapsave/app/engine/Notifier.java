package com.snapsave.app.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.snapsave.app.R;

/**
 * Per-download notifications.
 *
 * Each task owns its own notification id, so three downloads running in parallel update three
 * independent progress lines and finishing one never cancels the others. A finished download
 * reuses its own id, replacing its progress line with a "Download complete" entry.
 */
public final class Notifier {

    public static final String CHANNEL = "downloads_v2";

    private Notifier() {
    }

    /**
     * Stable per-task notification id derived from the task id.
     *
     * A plain {@code hashCode() % 100000} clusters badly (short hex strings collide often) and
     * two tasks sharing an id means one download's "complete" cancels the other's progress line.
     * FNV-1a over the id bytes, folded into a wide reserved range, keeps the id stable across
     * process restarts — required, because a resumed download must replace its own old line.
     */
    public static int idFor(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return 100000;
        }
        final int FNV_OFFSET = 0x811c9dc5;
        final int FNV_PRIME = 0x01000193;
        int h = FNV_OFFSET;
        for (int i = 0; i < taskId.length(); i++) {
            h ^= taskId.charAt(i) & 0xff;
            h *= FNV_PRIME;
        }
        // Second mixing pass: FNV alone maps similar ids (hex prefixes) to adjacent values.
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return 100000 + ((h & 0x7fffffff) % 900000);
    }

    /** True when the user can actually see notifications (permission / app setting). */
    public static boolean enabled(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                NotificationManager m = nm(c);
                if (m != null && !m.areNotificationsEnabled()) {
                    return false;
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                // PackageManager.checkPermission (API 1) rather than Context.checkSelfPermission
                // (API 23): minSdk here is 21, and this must never trip the verifier.
                return c.getPackageManager().checkPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS, c.getPackageName())
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = nm(c);
            if (m == null || m.getNotificationChannel(CHANNEL) != null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    c.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(c.getString(R.string.channel_downloads_desc));
            ch.setShowBadge(true);
            m.createNotificationChannel(ch);
        }
    }

    public static void progress(Context c, int id, String title, int percent) {
        try {
            NotificationManager m = nm(c);
            if (m == null || !enabled(c)) {
                return;
            }
            boolean known = percent > 0;
            Notification.Builder b = builder(c)
                    .setContentTitle(title == null ? c.getString(R.string.downloading) : title)
                    .setContentText(known ? percent + "%" : c.getString(R.string.downloading))
                    .setSmallIcon(R.drawable.ic_download)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openApp(c))
                    .setProgress(100, Math.max(0, Math.min(100, percent)), !known);
            m.notify(id, b.build());
        } catch (Throwable ignored) {
        }
    }

    /** Replace this task's progress line with a completion notification. */
    public static void done(Context c, int id, String title, String path) {
        done(c, id, title, path, null);
    }

    public static void done(Context c, int id, String title, String path, String warning) {
        try {
            NotificationManager m = nm(c);
            if (m == null || !enabled(c)) {
                return;
            }
            boolean hasWarning = warning != null && !warning.isEmpty();
            String body = (title == null ? "Media" : title) + (path == null ? "" : "\n" + path);
            if (hasWarning) body += "\n" + ("video_only_no_audio".equals(warning)
                    ? "Video saved without an audio track" : "Check the download details for a warning");
            Notification.Builder b = builder(c)
                    .setContentTitle(hasWarning ? "Download completed with warning" : c.getString(R.string.download_complete))
                    .setContentText(title == null ? "" : title)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_download)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(c));
            m.notify(id, b.build());
        } catch (Throwable ignored) {
        }
    }

    /** Drop one task's live progress line (paused or failed). */
    public static void clearProgress(Context c, int id) {
        NotificationManager m = nm(c);
        if (m != null) {
            m.cancel(id);
        }
    }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c) {
        return Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, com.snapsave.app.ui.MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(c, 1, i, flags);
    }
}