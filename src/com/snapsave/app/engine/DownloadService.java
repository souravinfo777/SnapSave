package com.snapsave.app.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.util.Util;

import java.util.List;

/** Foreground lifetime and aggregate progress for the download queue. */
public class DownloadService extends Service implements DownloadManager.Listener {
    private static final int ID = 42;
    private static final String CHANNEL = "download_service";
    private static final String PAUSE = "com.snapsave.app.PAUSE_DOWNLOADS";
    private static final String RESUME = "com.snapsave.app.RESUME_DOWNLOADS";
    static final String PAUSE_TASK = "com.snapsave.app.PAUSE_TASK";
    static final String CANCEL_TASK = "com.snapsave.app.CANCEL_TASK";
    private static volatile boolean running;
    private static volatile boolean startPending;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private boolean updatePending;
    private boolean pausedByUser;
    private long lastUpdate;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            updatePending = false;
            lastUpdate = SystemClock.elapsedRealtime();
            updateNotification();
        }
    };

    public static boolean isRunning() { return running; }

    public static synchronized boolean ensure(Context c) {
        if (running || startPending) return true;
        try {
            Intent i = new Intent(c, DownloadService.class);
            startPending = true;
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (RuntimeException e) { startPending = false; return false; }
    }

    public static void refresh(Context c) {
        // The manager listener schedules updates without starting a background service.
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Background downloads", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Progress for transfers, media processing, and saving downloaded files.");
                nm.createNotificationChannel(channel);
            }
            startForeground(ID, notification());
            running = true; startPending = false;
            App.get().downloads().addListener(this);
        } catch (RuntimeException e) {
            running = false; startPending = false;
            App.get().downloads().pauseAll(); stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) return START_NOT_STICKY;
        String action = intent == null ? null : intent.getAction();
        DownloadManager dm = App.get().downloads();
        if (PAUSE_TASK.equals(action) || CANCEL_TASK.equals(action)) {
            String id = intent.getStringExtra("task_id");
            for (DownloadTask task : dm.active()) {
                if (task.id != null && task.id.equals(id)) {
                    if (CANCEL_TASK.equals(action)) dm.remove(task);
                    else if (pausable(task)) dm.pause(task);
                    break;
                }
            }
        } else if (PAUSE.equals(action)) {
            pausedByUser = true;
            for (DownloadTask task : dm.active()) if (pausable(task)) dm.pause(task);
        } else if (RESUME.equals(action)) {
            // Retained for old notifications. The screen applies the legacy permission gate.
            try { Notifier.openApp(this).send(); } catch (PendingIntent.CanceledException ignored) { }
        } else dm.resumeRecovered();
        scheduleUpdate();
        return START_NOT_STICKY;
    }

    @Override public void onDownloadsChanged() { scheduleUpdate(); }

    private void scheduleUpdate() {
        if (updatePending) return;
        updatePending = true;
        long delay = Math.max(0, 500 - (SystemClock.elapsedRealtime() - lastUpdate));
        main.postDelayed(update, delay);
    }

    private static boolean pausable(DownloadTask t) {
        return t.state == DownloadTask.State.DOWNLOADING || t.state == DownloadTask.State.QUEUED
                || t.state == DownloadTask.State.WAITING_NETWORK;
    }

    private Notification notification() {
        List<DownloadTask> tasks = App.get().downloads().active();
        int downloading = 0, waiting = 0, processing = 0, saving = 0;
        long done = 0, total = 0;
        double speed = 0;
        boolean unknown = false, canPause = false;
        for (DownloadTask task : tasks) {
            canPause |= pausable(task);
            if (task.state == DownloadTask.State.MERGING || task.state == DownloadTask.State.CONVERTING) {
                processing++; continue;
            }
            if (task.state == DownloadTask.State.WAITING_NETWORK) { waiting++; continue; }
            if (task.state != DownloadTask.State.DOWNLOADING && task.state != DownloadTask.State.QUEUED
                    && task.state != DownloadTask.State.PUBLISHING) continue;
            boolean publish = task.state == DownloadTask.State.PUBLISHING;
            if (publish) saving++;
            else if (task.state == DownloadTask.State.DOWNLOADING) { downloading++; speed += task.speed; }
            else waiting++;
            done += Math.max(0, publish ? task.publishDone : task.done());
            total += Math.max(0, publish ? task.publishTotal : task.total());
            unknown |= !Notifier.knownSize(task);
        }
        String status = downloading + " downloading";
        if (waiting > 0) status += " • " + waiting + " waiting";
        if (processing > 0) status += " • " + processing + " processing";
        if (saving > 0) status += " • " + saving + " saving";
        boolean hasProgress = downloading + saving > 0 || total > 0;
        String bytes = total > 0 && !unknown ? Notifier.percent(done, total) + "% • " + Util.bytes(done)
                + " / " + Util.bytes(total) : Util.bytes(done) + " • size unknown";
        String detail = status + (hasProgress ? "\n" + bytes : "")
                + (downloading > 0 ? " • " + Util.speed(Math.max(0, speed)) : "");
        Notification.Builder b = builder().setSmallIcon(R.drawable.ic_download)
                .setContentTitle("SnapSave downloads").setContentText(detail.replace('\n', ' '))
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(Notifier.openApp(this)).setOngoing(true).setOnlyAlertOnce(true)
                .setShowWhen(false).setProgress(100, Notifier.percent(done, total), unknown || !hasProgress);
        if (canPause) {
            Intent pause = new Intent(this, DownloadService.class).setAction(PAUSE);
            b.addAction(R.drawable.ic_pause, "Pause transfers", PendingIntent.getService(this, 41, pause, pendingFlags()));
        }
        return b.build();
    }

    static PendingIntent taskAction(Context c, String action, String taskId) {
        Intent intent = new Intent(c, DownloadService.class).setAction(action)
                .setData(new Uri.Builder().scheme("snapsave").authority("download")
                        .appendPath(taskId == null ? "" : taskId).appendPath(action).build())
                .putExtra("task_id", taskId);
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(c, 0, intent, pendingFlags())
                : PendingIntent.getService(c, 0, intent, pendingFlags());
    }

    private Notification pausedNotification() {
        return builder().setSmallIcon(R.drawable.ic_download).setContentTitle("Downloads paused")
                .setContentText("Open Downloads to resume")
                .setContentIntent(Notifier.openApp(this)).setAutoCancel(true)
                .addAction(R.drawable.ic_play, "Open Downloads", Notifier.openApp(this)).build();
    }

    private void updateNotification() {
        if (!running) return;
        DownloadManager dm = App.get().downloads();
        for (DownloadTask task : dm.active()) Notifier.status(this, task);
        if (!dm.hasRunnableTasks()) {
            boolean showResume = pausedByUser && !dm.active().isEmpty();
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            if (showResume && manager != null && Notifier.enabled(this)) manager.notify(ID, pausedNotification());
            stopSelf(); return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(ID, notification());
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }
    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    }

    @Override public void onTimeout(int startId, int fgsType) {
        App.get().downloads().pauseAll();
        stopForeground(true); stopSelf();
    }
    @Override public void onDestroy() {
        running = false; startPending = false;
        main.removeCallbacksAndMessages(null); updatePending = false;
        App.get().downloads().removeListener(this);
        if (App.get().downloads().hasRunnableTasks()) App.get().downloads().pauseAll();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
