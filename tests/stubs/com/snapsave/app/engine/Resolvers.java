package com.snapsave.app.engine;

/** Controlled refresh backend; no extraction or matching logic belongs in this stub. */
public final class Resolvers {
    public static final String YT_MEDIA_UA = "host-test-youtube-client";
    static ResolvedMedia result;
    static Runnable afterResolve;
    static int calls;
    static String requestedPage;

    private Resolvers() { }

    public static ResolvedMedia resolve(String page) {
        calls++;
        requestedPage = page;
        if (afterResolve != null) afterResolve.run();
        return result;
    }

    public static boolean isYouTube(String page) {
        return false; // These fixtures use example.test; this stub does not test URL routing.
    }

    static void reset() {
        result = null;
        afterResolve = null;
        calls = 0;
        requestedPage = null;
    }
}
