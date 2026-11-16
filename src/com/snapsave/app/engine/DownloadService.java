package com.snapsave.app.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.ui.MainActivity;

/** Foreground lifetime for the existing download queue. */
public class DownloadService extends Service implements DownloadManager.Listener {
    private static final int ID = 42;
    private static final String CHANNEL = "download_service";
    private static final String PAUSE = "com.snapsave.app.PAUSE_DOWNLOADS";
    private static final String RESUME = "com.snapsave.app.RESUME_DOWNLOADS";
    private boolean pausedByUser;
    private static volatile boolean running;
    private static volatile boolean startPending;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final Runnable update = new Runnable() {
        @Override public void run() { updateNotification(); }
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
        } catch (RuntimeException e) {
            startPending = false;
            return false;
        }
    }

    public static void refresh(Context c) {
        // DownloadManager.Listener delivers the actual updates; do not create a service here.
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Background downloads",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Keeps active transfers running while SnapSave is in the background.");
                nm.createNotificationChannel(ch);
            }
            startForeground(ID, notification());
            running = true;
            startPending = false;
            App.get().downloads().addListener(this);
        } catch (RuntimeException e) {
            running = false;
            startPending = false;
            App.get().downloads().pauseAll();
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) return START_NOT_STICKY;
        if (intent != null && PAUSE.equals(intent.getAction())) {
            pausedByUser = true;
            App.get().downloads().pauseAll();
        } else if (intent != null && RESUME.equals(intent.getAction())) {
            pausedByUser = false;
            App.get().downloads().resumeAll();
        }
        App.get().downloads().resumeRecovered();
        main.removeCallbacks(update);
        main.postDelayed(update, 500);
        return START_NOT_STICKY;
    }

    @Override public void onDownloadsChanged() {
        main.removeCallbacks(update);
        main.postDelayed(update, 300);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);
        Intent pause = new Intent(this, DownloadService.class).setAction(PAUSE);
        PendingIntent action = PendingIntent.getService(this, 1, pause, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int count = App.get().downloads().activeCount();
        return b.setSmallIcon(R.drawable.ic_download)
                .setContentTitle("SnapSave downloads")
                .setContentText(count > 0 ? count + " active download" + (count == 1 ? "" : "s") : "Preparing downloads…")
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_download, "Pause all", action).build();
    }

    private Notification pausedNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent resume = new Intent(this, DownloadService.class).setAction(RESUME);
        PendingIntent action = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, 2, resume, flags)
                : PendingIntent.getService(this, 2, resume, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_download).setContentTitle("Downloads paused")
                .setContentText("Tap to resume your downloads")
                .setContentIntent(action).setAutoCancel(true)
                .addAction(R.drawable.ic_download, "Resume", action).build();
    }

    private void updateNotification() {
        if (!running) return;
        if (!App.get().downloads().hasRunnableTasks()) {
            boolean showResume = pausedByUser && !App.get().downloads().active().isEmpty();
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            if (showResume && manager != null && Notifier.enabled(this)) manager.notify(ID, pausedNotification());
            stopSelf();
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, notification());
    }

    // API 35 dataSync services have a limited background budget.
    @Override public void onTimeout(int startId, int fgsType) {
        App.get().downloads().pauseAll();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        startPending = false;
        main.removeCallbacksAndMessages(null);
        App.get().downloads().removeListener(this);
        if (App.get().downloads().hasRunnableTasks()) App.get().downloads().pauseAll();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
