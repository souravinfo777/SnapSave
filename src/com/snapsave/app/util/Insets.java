package com.snapsave.app.util;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Applies system-bar insets as padding. Needed because apps targeting Android 15 (API 35)
 * are drawn edge-to-edge (content would otherwise sit under the status/navigation bars).
 */
public final class Insets {

    private Insets() {
    }

    public static void apply(final View root) {
        if (root == null) {
            return;
        }
        final int pl = root.getPaddingLeft();
        final int pt = root.getPaddingTop();
        final int pr = root.getPaddingRight();
        final int pb = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets wi) {
                int top, bottom, left, right;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = wi.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                    left = bars.left;
                    right = bars.right;
                } else {
                    top = wi.getSystemWindowInsetTop();
                    bottom = wi.getSystemWindowInsetBottom();
                    left = wi.getSystemWindowInsetLeft();
                    right = wi.getSystemWindowInsetRight();
                }
                v.setPadding(pl + left, pt + top, pr + right, pb + bottom);
                return wi;
            }
        });
        root.requestApplyInsets();
    }

    /** Convenience: inset the activity's content container. */
    public static void applyToContent(android.app.Activity activity) {
        apply(activity.findViewById(android.R.id.content));
    }
}
