package com.snapsave.app.ui;

import android.view.LayoutInflater;
import android.view.View;

/** A tab screen: a root view managed by {@link MainActivity}. */
public abstract class Screen {

    protected final MainActivity act;
    public final View root;

    Screen(MainActivity act, int layout) {
        this.act = act;
        this.root = LayoutInflater.from(act).inflate(layout, null);
    }

    /** Called every time this screen becomes visible. */
    public void onShow() {
    }

    /**
     * Called once when the host activity is destroyed. Screens that register listeners on the
     * app-wide DownloadManager must unregister here, or the singleton keeps the whole Activity
     * (and its views) alive after every recreation.
     */
    public void destroy() {
    }
}
