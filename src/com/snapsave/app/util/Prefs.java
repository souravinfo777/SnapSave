package com.snapsave.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.snapsave.app.App;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persisted download defaults and separately scoped, encrypted credentials. */
public final class Prefs {
    public static final String DEFAULT_RESOLVER = "";

    /**
     * Local/test builds may supply assets/apikeys.txt. APK assets are extractable, so this is
     * not secure credential distribution. Production credentials should be entered in Settings.
     * No real key belongs in source control or in this placeholder.
     */
    public static final String DEFAULT_API_KEY = "YOUR_FASTSAVER_KEY_HERE";

    private static final String FASTSAVER_KEYS = "fastsaver_keys_encrypted_v1";
    private static final String RESOLVER_TOKEN = "resolver_token_encrypted_v1";
    private static final String RESOLVER_ORIGIN = "resolver_token_origin_v1";
    private static final String FASTSAVER_PURPOSE = "snapsave:fastsaver:keys:v1";
    private static String keysError = "";
    private static String tokenError = "";
    private static String resolverError = "";
    // A failed commit can still change SharedPreferences' in-memory map. Block use until saved.
    private static boolean keysBlocked;
    private static boolean tokenBlocked;
    private static boolean resolverBlocked;

    private Prefs() {
    }

    private static Context context() {
        return App.get() == null ? null : App.ctx();
    }

    private static SharedPreferences sp() {
        Context c = context();
        return c == null ? null : c.getSharedPreferences("snapsave", Context.MODE_PRIVATE);
    }

    private static SharedPreferences requirePreferences() {
        SharedPreferences s = sp();
        if (s == null) {
            throw new IllegalStateException("Settings are unavailable. Reopen the app and retry.");
        }
        return s;
    }

    private static void save(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("Could not save settings on this device. Retry before continuing.");
        }
    }

    // ---------------------------------------------------------------- resolver

    /** Invalid legacy endpoints are disabled rather than used for credential requests. */
    public static synchronized String resolver() {
        if (resolverBlocked) {
            return "";
        }
        SharedPreferences s = sp();
        try {
            String value = s == null ? DEFAULT_RESOLVER : s.getString("resolver", DEFAULT_RESOLVER);
            if (value == null || value.trim().isEmpty()) {
                return "";
            }
            normalizedOrigin(value.trim());
            resolverError = "";
            return value.trim();
        } catch (RuntimeException e) {
            resolverError = "Resolver disabled: enter a valid HTTPS endpoint without a username or password.";
            return "";
        }
    }

    /** HTTPS scheme + lowercase host + effective port; path/query do not change the origin. */
    private static String normalizedOrigin(String endpoint) {
        try {
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            int port = uri.getPort();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || host == null || host.isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || port == 0 || port > 65535 || port < -1
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalStateException();
            }
            host = host.toLowerCase(Locale.US);
            return "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
        } catch (Exception e) {
            // Do not include user-provided endpoints (which might contain credentials) in errors.
            throw new IllegalStateException("Use an HTTPS resolver URL with a valid host and port, "
                    + "without a username, password, or fragment.");
        }
    }

    /** Changing host, effective port, scheme, or disabling the endpoint clears its token. */
    public static synchronized void setResolver(String value) {
        String endpoint = value == null ? "" : value.trim();
        String origin = endpoint.isEmpty() ? "" : normalizedOrigin(endpoint);
        SharedPreferences s = requirePreferences();
        String oldOrigin = "";
        try {
            String old = s.getString("resolver", "");
            if (old != null && !old.trim().isEmpty()) {
                oldOrigin = normalizedOrigin(old.trim());
            }
        } catch (RuntimeException ignored) {
            // An invalid legacy endpoint has no trusted origin.
        }
        boolean clearToken = origin.isEmpty() || !origin.equals(oldOrigin);
        SharedPreferences.Editor editor = s.edit().putString("resolver", endpoint);
        if (clearToken) {
            editor.remove(RESOLVER_TOKEN).remove(RESOLVER_ORIGIN);
        }
        try {
            save(editor);
            resolverBlocked = false;
            resolverError = "";
            if (clearToken) {
                tokenBlocked = false;
                tokenError = "";
            }
        } catch (IllegalStateException e) {
            resolverBlocked = true;
            resolverError = "Resolver disabled because its settings could not be saved. Save the endpoint again.";
            throw e;
        }
    }

    /** Only the token for the currently configured HTTPS origin, never a FastSaver key. */
    public static synchronized String resolverToken() {
        if (tokenBlocked || resolverBlocked) {
            return "";
        }
        SharedPreferences s = sp();
        if (s == null) {
            return "";
        }
        try {
            String encrypted = s.getString(RESOLVER_TOKEN, null);
            if (encrypted == null || encrypted.isEmpty()) {
                return "";
            }
            String endpoint = resolver();
            String origin = endpoint.isEmpty() ? "" : normalizedOrigin(endpoint);
            if (origin.isEmpty() || !origin.equals(s.getString(RESOLVER_ORIGIN, ""))) {
                // Defend against stale/restored or directly edited preferences as well as setters.
                save(s.edit().remove(RESOLVER_TOKEN).remove(RESOLVER_ORIGIN));
                tokenError = "Resolver token cleared because its HTTPS origin changed. Enter a new token if needed.";
                return "";
            }
            String token = SecureCredentials.open("snapsave:resolver:token:v1:" + origin, encrypted);
            tokenError = "";
            return token;
        } catch (RuntimeException e) {
            tokenBlocked = true;
            tokenError = "Resolver token unavailable. Unlock the device and save the token again, or clear it.";
            return "";
        }
    }

    /** Nonempty tokens require a valid HTTPS endpoint; empty/null explicitly clears the token. */
    public static synchronized void setResolverToken(String value) {
        String token = value == null ? "" : value.trim();
        validateHeaderCredential(token);
        SharedPreferences s = requirePreferences();
        SharedPreferences.Editor editor = s.edit();
        try {
            if (token.isEmpty()) {
                editor.remove(RESOLVER_TOKEN).remove(RESOLVER_ORIGIN);
            } else {
                String endpoint = resolver();
                if (endpoint.isEmpty()) {
                    throw new IllegalStateException("Save a valid HTTPS resolver endpoint before entering its token.");
                }
                String origin = normalizedOrigin(endpoint);
                String encrypted = SecureCredentials.seal(context(), "snapsave:resolver:token:v1:" + origin, token);
                editor.putString(RESOLVER_TOKEN, encrypted).putString(RESOLVER_ORIGIN, origin);
            }
            save(editor);
            tokenBlocked = false;
            tokenError = "";
        } catch (IllegalStateException e) {
            tokenBlocked = true;
            tokenError = "Resolver token was not saved. Save it again, or clear it before continuing.";
            throw e;
        }
    }

    // ---------------------------------------------------------------- download defaults

    public static boolean wifiOnly() {
        SharedPreferences s = sp();
        return s != null && s.getBoolean("wifiOnly", false);
    }

    public static void setWifiOnly(boolean value) {
        save(requirePreferences().edit().putBoolean("wifiOnly", value));
    }

    /** Parallel HTTP range connections for one media part; 1 disables segmented downloading. */
    public static int downloadConnections() {
        SharedPreferences s = sp();
        int value = s == null ? 4 : s.getInt("downloadConnections", 4);
        return value >= 1 && value <= 8 ? value : 4;
    }

    public static void setDownloadConnections(int value) {
        if (value < 1 || value > 8) {
            throw new IllegalStateException("Choose between 1 and 8 download connections.");
        }
        save(requirePreferences().edit().putInt("downloadConnections", value));
    }

    /** How many downloads may transfer at the same time on Wi-Fi or mobile data. */
    public static int simultaneousDownloads() {
        SharedPreferences s = sp();
        int value = s == null ? 3 : s.getInt("simultaneousDownloads", 3);
        return value >= 1 && value <= 8 ? value : 3;
    }

    public static void setSimultaneousDownloads(int value) {
        if (value < 1 || value > 8) {
            throw new IllegalStateException("Choose between 1 and 8 simultaneous downloads.");
        }
        save(requirePreferences().edit().putInt("simultaneousDownloads", value));
    }

    /** Preferred maximum video height in pixels; 0 means best available. */
    public static int videoQuality() {
        SharedPreferences s = sp();
        return s == null ? 0 : Math.max(0, s.getInt("videoQuality", 0));
    }

    public static void setVideoQuality(int value) {
        if (value < 0) {
            throw new IllegalStateException("Choose Best or a positive video height.");
        }
        save(requirePreferences().edit().putInt("videoQuality", value));
    }

    public static String audioFormat() {
        SharedPreferences s = sp();
        return s != null && "m4a".equals(s.getString("audioFormat", "mp3")) ? "m4a" : "mp3";
    }

    public static void setAudioFormat(String value) {
        String format = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (!"mp3".equals(format) && !"m4a".equals(format)) {
            throw new IllegalStateException("Choose MP3 or M4A audio.");
        }
        save(requirePreferences().edit().putString("audioFormat", format));
    }

    public static int mp3Bitrate() {
        SharedPreferences s = sp();
        int value = s == null ? 192 : s.getInt("mp3Bitrate", 192);
        return validBitrate(value) ? value : 192;
    }

    public static void setMp3Bitrate(int value) {
        if (!validBitrate(value)) {
            throw new IllegalStateException("Choose a supported MP3 bitrate between 32 and 320 kbps.");
        }
        save(requirePreferences().edit().putInt("mp3Bitrate", value));
    }

    private static boolean validBitrate(int value) {
        for (int bitrate : new int[]{32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}) {
            if (bitrate == value) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- output folder

    /** The phone's own Download folder. */
    public static final String FOLDER_DOWNLOAD = "download";
    /** Download/SnapSave, so SnapSave saves stay together. */
    public static final String FOLDER_SNAPSAVE = "snapsave";
    /** A folder the user picked with the system folder picker. */
    public static final String FOLDER_CUSTOM = "custom";

    /** Falls back to Download when a picked folder lost its permission. */
    public static String downloadFolder() {
        SharedPreferences s = sp();
        String value = s == null ? FOLDER_DOWNLOAD : s.getString("downloadFolder", FOLDER_DOWNLOAD);
        if (FOLDER_SNAPSAVE.equals(value)) {
            return FOLDER_SNAPSAVE;
        }
        if (FOLDER_CUSTOM.equals(value) && !downloadFolderUri().isEmpty()) {
            return FOLDER_CUSTOM;
        }
        return FOLDER_DOWNLOAD;
    }

    public static void setDownloadFolder(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (!FOLDER_DOWNLOAD.equals(mode) && !FOLDER_SNAPSAVE.equals(mode) && !FOLDER_CUSTOM.equals(mode)) {
            throw new IllegalStateException("Choose Download, Download/SnapSave or another folder.");
        }
        save(requirePreferences().edit().putString("downloadFolder", mode));
    }

    /** Tree URI of the picked folder; empty for the built-in choices. */
    public static String downloadFolderUri() {
        SharedPreferences s = sp();
        String value = s == null ? null : s.getString("downloadFolderUri", "");
        return value == null ? "" : value.trim();
    }

    /** Display name of the picked folder, so screens can name it without asking the provider. */
    public static String downloadFolderName() {
        SharedPreferences s = sp();
        String value = s == null ? null : s.getString("downloadFolderName", "");
        return value == null ? "" : value.trim();
    }

    /** Store a folder picked with ACTION_OPEN_DOCUMENT_TREE; the caller already holds the grant. */
    public static void setDownloadFolderCustom(String treeUri, String displayName) {
        String uri = treeUri == null ? "" : treeUri.trim();
        if (!uri.startsWith("content://")) {
            throw new IllegalStateException("That folder cannot be used. Pick it again.");
        }
        String name = displayName == null ? "" : displayName.trim();
        save(requirePreferences().edit()
                .putString("downloadFolder", FOLDER_CUSTOM)
                .putString("downloadFolderUri", uri)
                .putString("downloadFolderName", name));
    }

    // ---------------------------------------------------------------- clipboard

    /** Explicit opt-in to open a download sheet for a copied link on app resume. */
    public static boolean clipboardPrompt() {
        SharedPreferences s = sp();
        return s != null && s.getBoolean("clipboardPrompt", false);
    }

    public static void setClipboardPrompt(boolean value) {
        save(requirePreferences().edit().putBoolean("clipboardPrompt", value));
    }

    // ---------------------------------------------------------------- FastSaver keys

    /**
     * FastSaver keys only. First access encrypts and verifies legacy "apikeys", then atomically
     * commits ciphertext and plaintext removal. Failure disables credentials with no fallback.
     */
    public static synchronized List<String> keys() {
        if (keysBlocked) {
            return new ArrayList<String>();
        }
        SharedPreferences s = sp();
        if (s == null) {
            return new ArrayList<String>();
        }
        try {
            String encrypted = s.getString(FASTSAVER_KEYS, null);
            if (encrypted == null && s.contains("apikeys")) {
                String legacy = joinKeys(parseKeys(s.getString("apikeys", "")));
                encrypted = legacy.isEmpty() ? "" : SecureCredentials.seal(context(), FASTSAVER_PURPOSE, legacy);
                save(s.edit().putString(FASTSAVER_KEYS, encrypted).remove("apikeys"));
            } else if (encrypted != null && s.contains("apikeys")) {
                // Clean up stale plaintext only after confirming the secure value is readable.
                if (!encrypted.isEmpty()) {
                    SecureCredentials.open(FASTSAVER_PURPOSE, encrypted);
                }
                save(s.edit().remove("apikeys"));
            }
            String raw = encrypted == null ? localKeys()
                    : encrypted.isEmpty() ? "" : SecureCredentials.open(FASTSAVER_PURPOSE, encrypted);
            keysError = "";
            return parseKeys(raw);
        } catch (RuntimeException e) {
            keysBlocked = true;
            keysError = "FastSaver keys unavailable: secure storage or migration failed. "
                    + "Unlock the device and save your keys again, or clear them. No keys will be sent.";
            return new ArrayList<String>();
        }
    }

    private static List<String> parseKeys(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw != null) {
            for (String part : raw.split("[\\n,;]+")) {
                String key = part.trim();
                if (!key.isEmpty() && !DEFAULT_API_KEY.equalsIgnoreCase(key)) {
                    validateHeaderCredential(key);
                    out.add(key);
                }
            }
        }
        return out;
    }

    private static void validateHeaderCredential(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x20 || character >= 0x7f) {
                throw new IllegalStateException("Credentials must use printable characters without spaces or control characters.");
            }
        }
    }

    private static String joinKeys(List<String> keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(key);
        }
        return out.toString();
    }

    /** Local/test asset fallback only; encrypted preferences always take precedence. */
    private static String localKeys() {
        Context c = context();
        if (c != null) {
            try (java.io.InputStream in = c.getAssets().open("apikeys.txt");
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                return out.toString("UTF-8").trim();
            } catch (Exception ignored) {
                // No local test credential; the placeholder is filtered out by parseKeys().
            }
        }
        return DEFAULT_API_KEY;
    }

    /** Save only encrypted FastSaver keys. An explicit empty value also disables asset fallback. */
    public static synchronized void setKeys(String raw) {
        String value = joinKeys(parseKeys(raw));
        SharedPreferences s = requirePreferences();
        try {
            String encrypted = value.isEmpty() ? "" : SecureCredentials.seal(context(), FASTSAVER_PURPOSE, value);
            save(s.edit().putString(FASTSAVER_KEYS, encrypted).remove("apikeys").putInt("keyIndex", 0));
            keysBlocked = false;
            keysError = "";
        } catch (IllegalStateException e) {
            keysBlocked = true;
            keysError = "FastSaver keys were not saved. Save them again, or clear them before continuing.";
            throw e;
        }
    }

    /** Safe, non-secret status for Settings; also checks migration and decryptability. */
    public static synchronized String credentialError() {
        keys();
        resolver();
        resolverToken();
        StringBuilder out = new StringBuilder();
        for (String error : new String[]{keysError, resolverError, tokenError}) {
            if (!error.isEmpty()) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(error);
            }
        }
        return out.toString();
    }

    public static int keyCount() {
        return keys().size();
    }

    /** Legacy FastSaver-only accessor. Custom resolvers must use resolverToken(). */
    public static String apiKey() {
        List<String> keys = keys();
        return keys.isEmpty() ? "" : keys.get(currentKeyIndex() % keys.size());
    }

    public static int currentKeyIndex() {
        SharedPreferences s = sp();
        return s == null ? 0 : Math.max(0, s.getInt("keyIndex", 0));
    }

    public static void setCurrentKeyIndex(int value) {
        SharedPreferences s = sp();
        if (s != null) {
            s.edit().putInt("keyIndex", Math.max(0, value)).apply();
        }
    }
}
