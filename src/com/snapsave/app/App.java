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
        // Recovery starts when an activity is visible or the foreground service starts.
        // Starting a dataSync service from Application can violate background restrictions.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity activity) {
                downloadManager.resumeRecovered();
                downloadManager.onNetworkChanged();
            }
            @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
            @Override public void onActivityStarted(android.app.Activity activity) { }
            @Override public void onActivityPaused(android.app.Activity activity) { }
            @Override public void onActivityStopped(android.app.Activity activity) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
            @Override public void onActivityDestroyed(android.app.Activity activity) { }
        });
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
