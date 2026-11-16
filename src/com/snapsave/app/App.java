package com.snapsave.app;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.snapsave.app.engine.DownloadManager;
import com.snapsave.app.engine.Notifier;

public class App extends Application {

    private static App instance;
    private DownloadManager downloadManager;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Notifier.ensureChannel(this);
        downloadManager = new DownloadManager(this);
        downloadManager.loadPersisted();
        // Downloads that were running when the process died are restored as paused, then
        // resumed once the first screen has attached its listener (avoids a flash of 0%).
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (downloadManager != null) {
                    downloadManager.resumeRecovered();
                }
            }
        }, 1200);
    }

    public static App get() {
        return instance;
    }

    public static Context ctx() {
        return instance.getApplicationContext();
    }

    public DownloadManager downloads() {
        return downloadManager;
    }
}
