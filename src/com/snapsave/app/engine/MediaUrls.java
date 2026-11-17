package com.snapsave.app.engine;

import java.net.URI;

/** Pure URL checks shared by resolver results and the download picker. */
public final class MediaUrls {
    private MediaUrls() { }

    public static boolean hasText(String value) {
        if (value == null) return false;
        String text = value.trim();
        return !text.isEmpty() && !"null".equalsIgnoreCase(text)
                && !"undefined".equalsIgnoreCase(text);
    }

    public static boolean isHttps(String value) {
        if (!hasText(value) || !value.equals(value.trim())) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !uri.getHost().isEmpty() && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** TikWM uses absolute, protocol-relative and relative paths for every media field. */
    public static String tikwm(String value) {
        if (!hasText(value)) return null;
        try {
            String result = new URI("https://www.tikwm.com/").resolve(value.trim()).toString();
            return isHttps(result) ? result : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
