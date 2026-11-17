package com.snapsave.app.engine;

import com.snapsave.app.model.StreamOption;

/** Pure host checks; add this main to the host runner alongside MediaUrls.java. */
public final class ResolvedMediaValidityRegression {
    public static void main(String[] args) {
        ResolvedMedia media = new ResolvedMedia();
        media.progressive = true;
        media.directUrl = "https://cdn.example/video.mp4";
        check(!media.hasAnything(), "progressive metadata without a selectable row must fail");
        media.videoStreams.add(null);
        StreamOption invalid = new StreamOption();
        invalid.url = "javascript:alert(1)";
        media.videoStreams.add(invalid);
        check(!media.hasAnything(), "null and invalid rows must fail");
        invalid.url = "http://cdn.example/video.mp4";
        check(!media.hasAnything(), "cleartext row must fail");
        invalid.url = "https:///missing-host";
        check(!media.hasAnything(), "HTTPS scheme alone must fail");
        invalid.url = "https://cdn.example/video.mp4?token=signed%2Fvalue";
        check(media.hasAnything(), "valid direct row must succeed");
        check(media.usableCount(media.videoStreams) == 1, "count only usable rows");
        invalid.url = null;
        invalid.serverFormat = "1080p";
        check(!media.hasAnything(), "token without a supported provider must fail");
        media.serverName = "fastsaver";
        check(!media.hasAnything(), "lazy token needs a source page");
        media.pageUrl = "https://www.youtube.com/watch?v=abcdefghijk";
        check(media.hasAnything(), "valid lazy row must succeed");
        for (String token : new String[]{null, "", "  ", "null", " undefined "}) {
            invalid.serverFormat = token;
            check(!media.hasAnything(), "empty/JSON-null token must fail: " + token);
        }
        invalid.serverFormat = "mp3";
        media.pageUrl = "http://www.youtube.com/watch?v=abcdefghijk";
        check(!media.hasAnything(), "lazy source page must be HTTPS");
        media.pageUrl = "https://www.youtube.com/watch?v=abcdefghijk";
        media.serverName = "unknown-provider";
        check(!media.hasAnything(), "unsupported lazy provider must fail");
        media.videoStreams.clear();
        StreamOption audio = new StreamOption();
        audio.audioOnly = true;
        audio.url = "https://cdn.example/audio.m4a";
        media.audioStreams.add(audio);
        check(media.hasAnything(), "audio-only direct row must succeed");

        for (String url : new String[]{null, "", "null", "https://", "https:///file",
                " https://cdn.example/file", "https://cdn.example/file ", "http://cdn.example/file",
                "file:///video.mp4", "https://user:pass@cdn.example/file", "https://cdn.example:0/file",
                "https://cdn.example:65536/file", "https://cdn.example/a b"}) {
            check(!MediaUrls.isHttps(url), "reject unusable HTTPS URL: " + url);
        }
        check(MediaUrls.isHttps("HTTPS://cdn.example:443/file"), "scheme is case insensitive");
        check(MediaUrls.isHttps("https://cdn.example/file?x=a%20b&y=1"), "preserve signed query");
        equal("https://www.tikwm.com/video/file.mp4", MediaUrls.tikwm("/video/file.mp4"));
        equal("https://www.tikwm.com/video/file.mp4", MediaUrls.tikwm("video/file.mp4"));
        equal("https://cdn.example/audio.mp3?token=abc", MediaUrls.tikwm("//cdn.example/audio.mp3?token=abc"));
        equal("https://cdn.example/cover.jpg", MediaUrls.tikwm(" https://cdn.example/cover.jpg "));
        for (String url : new String[]{null, "", "null", " undefined ", "http://cdn.example/file",
                "javascript:alert(1)", "file:///video.mp4", "https:///file", "bad path.mp4"}) {
            check(MediaUrls.tikwm(url) == null, "reject invalid TikWM field: " + url);
        }
        System.out.println("ResolvedMediaValidityRegression passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(String expected, String actual) {
        check(expected.equals(actual), "expected " + expected + ", got " + actual);
    }
}
