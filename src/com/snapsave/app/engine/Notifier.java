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

    /** Stable per-task notification id derived from the task id. */
    public static int idFor(String taskId) {
        int h = taskId == null ? 0 : taskId.hashCode();
        return 1000 + ((h & 0x7fffffff) % 100000);
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
            if (m == null) {
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
        try {
            NotificationManager m = nm(c);
            if (m == null) {
                return;
            }
            String body = (title == null ? "Media" : title) + (path == null ? "" : "\n" + path);
            Notification.Builder b = builder(c)
                    .setContentTitle(c.getString(R.string.download_complete))
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