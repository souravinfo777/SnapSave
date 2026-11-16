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
}
