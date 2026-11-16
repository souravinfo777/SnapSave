package com.snapsave.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.snapsave.app.App;

import java.util.ArrayList;
import java.util.List;

/** App preferences: resolver backend, API keys (rotated), API key index. */
public final class Prefs {

    /**
     * Off by default: FastSaver (plus the built-in extractors) is the primary resolver.
     * A custom POST endpoint can still be configured from Settings if ever needed.
     */
    public static final String DEFAULT_RESOLVER = "";

    /**
     * Fallback FastSaver API key(s) — X-Api-Key, one per line. Several keys (different accounts)
     * are rotated automatically.
     *
     * <p>The real keys are NOT committed: drop them in {@code assets/apikeys.txt} (git-ignored) or
     * enter them once in Settings. This placeholder only exists so a clean checkout still builds.
     */
    public static final String DEFAULT_API_KEY = "YOUR_FASTSAVER_KEY_HERE";

    private Prefs() {
    }

    private static SharedPreferences sp() {
        Context c = App.ctx();
        return c == null ? null : c.getSharedPreferences("snapsave", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- backend

    public static String resolver() {
        SharedPreferences s = sp();
        return s == null ? DEFAULT_RESOLVER : s.getString("resolver", DEFAULT_RESOLVER);
    }

    public static void setResolver(String v) {
        SharedPreferences s = sp();
        if (s != null) {
            s.edit().putString("resolver", v == null ? "" : v.trim()).apply();
        }
    }

    // ---------------------------------------------------------------- clipboard

    /**
     * Open the download sheet for a freshly copied link on app resume.
     *
     * Off by default: silently popping a full-screen resolver over whatever the user was doing
     * is a surprise. The Browse screen's paste button (and share/open-with) covers the same link
     * without hijacking app entry.
     */
    public static boolean clipboardPrompt() {
        SharedPreferences s = sp();
        return s != null && s.getBoolean("clipboardPrompt", false);
    }

    public static void setClipboardPrompt(boolean v) {
        SharedPreferences s = sp();
        if (s != null) {
            s.edit().putBoolean("clipboardPrompt", v).apply();
        }
    }

    // ---------------------------------------------------------------- API keys

    /** All configured FastSaver keys (one per line). Empty list disables FastSaver. */
    public static List<String> keys() {
        SharedPreferences s = sp();
        String raw = s == null ? null : s.getString("apikeys", null);
        if (raw == null) {
            raw = localKeys();
        }
        List<String> out = new ArrayList<String>();
        if (raw != null) {
            for (String part : raw.split("[\\n,;]+")) {
                String t = part.trim();
                // A placeholder must never masquerade as a configured key: it would make the
                // app spend a request (and time) on a key that can only fail.
                if (!t.isEmpty() && !isPlaceholder(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static boolean isPlaceholder(String key) {
        return key.equals(DEFAULT_API_KEY) || "YOUR_FASTSAVER_KEY_HERE".equalsIgnoreCase(key);
    }

    /**
     * Keys from the untracked {@code assets/apikeys.txt} when present, else the placeholder.
     * Keeps real credentials out of version control while local builds keep working.
     */
    private static String localKeys() {
        Context c = App.ctx();
        if (c != null) {
            java.io.InputStream in = null;
            try {
                in = c.getAssets().open("apikeys.txt");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                String t = new String(bo.toByteArray(), "UTF-8").trim();
                if (!t.isEmpty()) {
                    return t;
                }
            } catch (Exception ignored) {
                // no bundled key file — fall through to the placeholder
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return DEFAULT_API_KEY;
    }

    public static void setKeys(String raw) {
        SharedPreferences s = sp();
        if (s != null) {
            s.edit().putString("apikeys", raw == null ? "" : raw).apply();
        }
    }

    public static int keyCount() {
        return keys().size();
    }

    /** Key currently preferred (sticky), wrapped into range. */
    public static String apiKey() {
        List<String> k = keys();
        if (k.isEmpty()) {
            return "";
        }
        int idx = currentKeyIndex() % k.size();
        return k.get(idx);
    }

    public static int currentKeyIndex() {
        SharedPreferences s = sp();
        return s == null ? 0 : s.getInt("keyIndex", 0);
    }

    public static void setCurrentKeyIndex(int i) {
        SharedPreferences s = sp();
        if (s != null) {
            s.edit().putInt("keyIndex", i).apply();
        }
    }
}
