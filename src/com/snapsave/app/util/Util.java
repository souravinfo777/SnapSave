package com.snapsave.app.util;

import android.content.Context;

import com.snapsave.app.R;

public final class Util {

    private static final int[] GRADS = {
            R.drawable.grad_purple, R.drawable.grad_teal, R.drawable.grad_green, R.drawable.grad_blue
    };

    private Util() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int grad(int i) {
        return GRADS[((i % GRADS.length) + GRADS.length) % GRADS.length];
    }

    public static String bytes(long b) {
        if (b <= 0) {
            return "—";
        }
        double kb = b / 1024.0;
        if (kb < 1024) {
            return String.format("%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return mb >= 100 ? String.format("%.0f MB", mb) : String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static String speed(double bps) {
        if (bps <= 0) {
            return "";
        }
        double kb = bps / 1024.0;
        if (kb < 1024) {
            return String.format("%.0f KB/s", kb);
        }
        return String.format("%.1f MB/s", kb / 1024.0);
    }

    /** First http(s) URL found on the system clipboard, or null. Safe to call anywhere. */
    public static String clipboardUrl(Context c) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                android.content.ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence t = clip.getItemAt(0).coerceToText(c);
                    if (t != null) {
                        return com.snapsave.app.engine.Resolvers.firstUrl(t.toString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String mmss(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    public static String ms(long millis) {
        int total = (int) (millis / 1000);
        return String.format("%02d:%02d", total / 60, total % 60);
    }
}
