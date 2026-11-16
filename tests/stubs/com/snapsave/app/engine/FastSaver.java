package com.snapsave.app.engine;

/** Deterministic signed-URL response only; production TaskRefresh chooses the format. */
public final class FastSaver {
    static String result;
    static Runnable afterDownloadUrl;
    static int calls;
    static String requestedPage;
    static String requestedFormat;

    private FastSaver() { }

    public static String downloadUrl(String page, String format) {
        calls++;
        requestedPage = page;
        requestedFormat = format;
        if (afterDownloadUrl != null) afterDownloadUrl.run();
        return result;
    }

    static void reset() {
        result = null;
        afterDownloadUrl = null;
        calls = 0;
        requestedPage = null;
        requestedFormat = null;
    }
}
