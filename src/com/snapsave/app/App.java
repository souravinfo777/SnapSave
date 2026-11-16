package com.snapsave.app;

import android.app.Application;
import android.content.Context;

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
