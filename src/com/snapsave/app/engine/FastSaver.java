package com.snapsave.app.engine;

import com.snapsave.app.model.MediaItem;
import com.snapsave.app.model.StreamOption;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FastSaver API client (https://api.fastsaver.io) — one request returns clean JSON for
 * YouTube (info/search/download) plus 8 more platforms via /v1/fetch.
 *
 * Auth: {@code X-Api-Key: <key>} header. Read the JSON {@code ok} field, not the HTTP status.
 */
public final class FastSaver {

    public static final String BASE = "https://api.fastsaver.io";

    private FastSaver() {
    }

    /** Number of configured keys (0 disables FastSaver). */
    public static int keyCount() {
        return com.snapsave.app.util.Prefs.keyCount();
    }

    /**
     * Rotating caller: tries each configured key, skipping ones in cooldown, and advances on
     * 401 (invalid) / 429 (rate limit) / 400 mentioning credits-quota, or 5xx.
     * Returns the raw body (even the final error body) so the caller can read {@code ok}.
     */
    private static String call(String url, String jsonBody) {
        List<String> keys = com.snapsave.app.util.Prefs.keys();
        int n = keys.size();
        if (n == 0) {
            return null;
        }
        int start = com.snapsave.app.util.Prefs.currentKeyIndex() % n;
        String lastBody = null;
        for (int i = 0; i < n; i++) {
            if (Http.requestExpired() || Thread.currentThread().isInterrupted()) {
                break;
            }
            int idx = (start + i) % n;
            String key = keys.get(idx);
            if (KeyPool.cooling(key)) {
                continue;
            }
            try {
                Http.Resp r = (jsonBody == null)
                        ? Http.getResp(url, key)
                        : Http.postResp(url, jsonBody, key);
                if (r.code == 200) {
                    if (quotaExhausted(r.body)) {
                        Http.noteFailureText(r.body);
                        // HTTP 200 with ok:false + a quota message: rest this key and try the next.
                        KeyPool.cool(key, 3600_000L);
                        lastBody = r.body;
                        continue;
                    }
                    com.snapsave.app.util.Prefs.setCurrentKeyIndex(idx);
                    return r.body;
                }
                lastBody = r.body;
                if (r.code == 401) {                 // invalid / revoked key
                    KeyPool.cool(key, 6 * 3600_000L);
                    continue;
                }
                if (r.code == 429) {                  // rate limit -> try next key
                    KeyPool.cool(key, 90_000L);
                    continue;
                }
                if (r.code >= 500) {                  // overloaded -> try next key
                    continue;
                }
                if (r.code == 400) {
                    String d = r.body == null ? "" : r.body.toLowerCase();
                    if (d.contains("credit") || d.contains("quota") || d.contains("exhaust")
                            || d.contains("limit") || d.contains("renew")) {
                        KeyPool.cool(key, 3600_000L); // out of credits -> rest this key
                        continue;
                    }
                    return r.body;                    // genuine client error
                }
                return r.body;
            } catch (Throwable t) {
                Http.noteFailure(t);
                lastBody = null;
                if (Http.requestExpired() || Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        return lastBody;
    }

    /** YouTube: metadata + available formats (direct URLs are fetched at download time). */
    public static ResolvedMedia info(String url) {
        try {
            String resp = call(BASE + "/v1/youtube/info?url=" + enc(url), null);
            if (resp == null) {
                return null;
            }
            JSONObject j = new JSONObject(resp);
            if (!j.optBoolean("ok", false)) {
                Http.noteFailureText(j.optString("error", "") + " " + j.optString("message", ""));
                return null;
            }
            ResolvedMedia r = new ResolvedMedia();
            r.serverName = "fastsaver";
            r.pageUrl = url;
            r.grad = Math.abs(url.hashCode()) % 4;
            r.title = orEmpty(Resolvers.firstText(j.optString("title", null), j.optString("caption", null)));
            r.author = j.optString("author", "");
            r.thumbUrl = j.optString("thumbnail", null);
            int dur = j.optInt("duration", 0);
            if (dur > 0) {
                r.duration = String.format("%d:%02d", dur / 60, dur % 60);
            }
            String vid = j.optString("video_id", null);
            if (vid != null && !vid.isEmpty()) {
                r.videoId = vid;
                r.pageUrl = "https://www.youtube.com/watch?v=" + vid;
            }

            JSONArray formats = j.optJSONArray("formats");
            if (formats != null) {
                boolean audioAdded = false;
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject f = formats.getJSONObject(i);
                    String type = f.optString("type", "");
                    String fmt = f.optString("format", "");
                    long size = f.optLong("filesize", 0);
                    if ("audio".equals(type)) {
                        // The backend always returns an MP3 for its audio token, so expose exactly
                        // one MP3 row no matter how many audio formats the payload lists.
                        if (!audioAdded) {
                            StreamOption mp3 = new StreamOption("MP3 • 320 kbps", "Music quality", size, 0, true);
                            mp3.mimeType = "audio/mpeg";
                            mp3.serverFormat = "mp3";
                            mp3.bitrate = 320;
                            r.audioStreams.add(mp3);
                            audioAdded = true;
                        }
                    } else {
                        int h = parseInt(fmt);
                        StreamOption o = new StreamOption();
                        o.label = h > 0 ? fmt : "Video";
                        o.sub = "MP4 • Server";
                        o.sizeBytes = size;
                        o.height = h;
                        o.mimeType = "video/mp4";
                        o.serverFormat = fmt;
                        o.progressive = true;
                        r.videoStreams.add(o);
                    }
                }
            }
            Collections.sort(r.videoStreams, new Comparator<StreamOption>() {
                @Override
                public int compare(StreamOption a, StreamOption b) {
                    return b.height - a.height;
                }
            });
            return r.hasAnything() ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** YouTube: resolve a chosen format token to a signed direct download URL. */
    public static String downloadUrl(String url, String format) {
        try {
            JSONObject body = new JSONObject();
            body.put("url", url);
            body.put("format", format);
            String resp = call(BASE + "/v1/youtube/download", body.toString());
            if (resp == null) {
                return null;
            }
            JSONObject j = new JSONObject(resp);
            if (!j.optBoolean("ok", false)) {
                Http.noteFailureText(j.optString("error", "") + " " + j.optString("message", ""));
                return null;
            }
            String du = j.optString("download_url", null);
            return (du != null && du.startsWith("http")) ? du : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Other platforms via /v1/fetch — parse whatever media URLs the JSON exposes. */
    public static ResolvedMedia fetch(String url) {
        try {
            String resp = call(BASE + "/v1/fetch?url=" + enc(url), null);
            if (resp == null) {
                return null;
            }
            JSONObject j = new JSONObject(resp);
            if (!j.optBoolean("ok", false)) {
                Http.noteFailureText(j.optString("error", "") + " " + j.optString("message", ""));
                return null;
            }
            ResolvedMedia r = new ResolvedMedia();
            r.pageUrl = url;
            r.grad = Math.abs(url.hashCode()) % 4;
            r.title = orEmpty(Resolvers.firstText(j.optString("title", null),
                    j.optString("caption", null), j.optString("description", null),
                    j.optString("message", null), j.optString("desc", null)));
            r.author = j.optString("author", j.optString("uploader", ""));
            r.thumbUrl = j.optString("thumbnail", j.optString("thumbnail_url", null));
            int dur = j.optInt("duration", 0);
            if (dur > 0) {
                r.duration = String.format("%d:%02d", dur / 60, dur % 60);
            }

            // 1) Explicit single-result shapes (TikTok / Facebook). These links often carry no
            //    file extension (e.g. .../aweme/v1/play/?...), which the generic scan below misses.
            String direct = firstHttp(j, "download_url", "video_url", "hdplay", "play", "video", "url");
            String audioUrl = firstHttp(j, "audio", "music", "audio_url", "sound");
            if (audioUrl != null && audioUrl.equals(direct)) {
                audioUrl = null;
            }
            // HLS playlists are not a single file: never promote one to the primary video row,
            // or the "mp4" it is saved as is really a text playlist.
            if (isHls(direct)) {
                direct = null;
            }
            if (isHls(audioUrl)) {
                audioUrl = null;
            }

            if (audioUrl != null) {
                StreamOption a = new StreamOption();
                a.url = audioUrl;
                a.mimeType = audioUrl.toLowerCase().contains(".m4a") ? "audio/mp4" : "audio/mpeg";
                a.label = "MP3 • Audio";
                a.sub = "Server audio";
                a.audioOnly = true;
                a.bitrate = 128;
                r.audioStreams.add(a);
                r.bestAudio = a;
            }

            if (direct != null) {
                StreamOption o = new StreamOption();
                o.url = direct;
                o.mimeType = guess(direct);
                int h = j.optInt("height", 0);
                if (h <= 0) {
                    h = Resolvers.heightFromUrl(direct);   // e.g. "...tag=dash_h264-basic-gen2_720p"
                }
                if (h > 0) {
                    o.height = h;
                    o.label = h + "p";
                } else {
                    o.label = "Original";
                }
                // Facebook and most other platforms come back without a size field. The media
                // URL still advertises its bitrate, so bytes = bitrate * duration (matches the
                // real Content-Length within ~1%).
                if (dur > 0) {
                    long bits = Resolvers.bitrateFromUrl(direct);
                    if (bits > 0) {
                        o.sizeBytes = bits * dur / 8;
                    }
                }
                o.sub = "Server • " + hostOf(direct);
                // A video-only stream is not progressive when a separate audio track exists —
                // it has to be muxed, otherwise the saved file is silent.
                o.progressive = (r.bestAudio == null);
                if (o.mimeType.startsWith("audio")) {
                    o.audioOnly = true;
                    r.audioStreams.add(o);
                } else {
                    r.videoStreams.add(o);
                }
            }

            // 2) Any other media-looking URL anywhere in the JSON payload.
            List<String[]> found = new ArrayList<String[]>();
            collect(j, found, null);
            for (String[] f : found) {
                String u = f[0];
                if (u == null || u.equals(direct) || u.equals(audioUrl)) {
                    continue;
                }
                if (u.toLowerCase().contains(".m3u8")) {
                    continue;                     // HLS playlist — not a single downloadable file
                }                StreamOption o = new StreamOption();
                o.url = u;
                o.mimeType = guess(u);
                o.label = (f[1] == null || f[1].isEmpty()) ? "Original" : f[1];
                o.sub = "Server • " + hostOf(u);
                o.progressive = (r.bestAudio == null);
                if (o.mimeType.startsWith("audio")) {
                    o.audioOnly = true;
                    r.audioStreams.add(o);
                } else if (o.mimeType.startsWith("image")) {
                    o.label = "Image";
                    o.sub = "Photo";
                    o.progressive = true;
                    r.videoStreams.add(o);
                } else {
                    r.videoStreams.add(o);
                }
            }
            return r.hasAnything() ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Never let a null title reach the UI / file name (e.g. {"title": null} from the API). */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** An HTTP 200 body can still report a quota failure — that must rotate to the next key. */
    private static boolean quotaExhausted(String body) {
        if (body == null || !body.contains("\"ok\"")) {
            return false;
        }
        String d = body.toLowerCase().replace(" ", "");
        if (!d.contains("\"ok\":false")) {
            return false;
        }
        return d.contains("credit") || d.contains("quota") || d.contains("exhaust")
                || d.contains("limit") || d.contains("renew") || d.contains("subscription");
    }

    private static String firstHttp(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && v.startsWith("http")) {
                return v;
            }
        }
        return null;
    }

    /** True for an HLS playlist reference (by extension or the common manifest query). */
    private static boolean isHls(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".m3u?") || u.contains("manifest.m3u")
                || u.contains("format=m3u8") || u.contains("type=m3u8");
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "server";
        }
    }

    /** YouTube search -> MediaItems. */
    public static List<MediaItem> search(String query, int page) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        try {
            String resp = call(BASE + "/v1/youtube/search?query=" + enc(query) + "&page=" + page, null);
            if (resp == null) {
                return out;
            }
            JSONObject j = new JSONObject(resp);
            if (!j.optBoolean("ok", false)) {
                return out;
            }
            JSONArray results = j.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.getJSONObject(i);
                    String id = o.optString("video_id", "");
                    String title = o.optString("title", "");
                    if (id.isEmpty() || title.isEmpty()) {
                        continue;
                    }
                    MediaItem m = new MediaItem();
                    m.id = id;
                    m.videoId = id;
                    m.title = title;
                    m.duration = o.optString("duration", "");
                    m.author = o.optString("author", "");
                    m.meta = o.optString("views", "");
                    m.thumbUrl = o.optString("thumbnail_url", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
                    m.pageUrl = "https://www.youtube.com/watch?v=" + id;
                    m.grad = Math.abs(id.hashCode()) % 4;
                    out.add(m);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // recursively pull any media-looking URL out of an unknown JSON shape
    private static void collect(Object node, List<String[]> out, String label) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String l = label;
            for (String k : new String[]{"quality", "label", "format", "type", "name"}) {
                if (o.has(k) && o.opt(k) instanceof String) {
                    l = o.optString(k);
                    break;
                }
            }
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.opt(k);
                if (v instanceof String && looksMedia((String) v)) {
                    out.add(new String[]{(String) v, l});
                } else {
                    collect(v, out, l);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                collect(a.opt(i), out, label);
            }
        }
    }

    private static boolean looksMedia(String s) {
        if (s == null || !s.startsWith("http")) {
            return false;
        }
        String u = s.toLowerCase();
        return u.contains(".mp4") || u.contains(".m4a") || u.contains(".mp3") || u.contains(".webm")
                || u.contains(".jpg") || u.contains(".jpeg") || u.contains(".png") || u.contains(".m3u8")
                || u.contains("/dl?") || u.contains("videoplayback");
    }

    private static String guess(String url) {
        String u = url.toLowerCase();
        if (u.contains(".mp3")) return "audio/mpeg";
        if (u.contains(".m4a") || u.contains(".aac")) return "audio/mp4";
        if (u.contains(".opus")) return "audio/opus";
        if (u.contains(".flac")) return "audio/flac";
        if (u.contains(".wav")) return "audio/wav";
        if (u.contains(".ogg") || u.contains(".oga")) return "audio/ogg";
        if (u.contains(".jpg") || u.contains(".jpeg")) return "image/jpeg";
        if (u.contains(".png")) return "image/png";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".gif")) return "image/gif";
        if (u.contains(".webm")) return "video/webm";
        if (u.contains(".mkv")) return "video/x-matroska";
        if (u.contains(".mov")) return "video/quicktime";
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        return "video/mp4";
    }

    /** Height from a format label like "1080p", "720p60" or "2160" (never "108060"). */
    private static int parseInt(String s) {
        if (s == null) {
            return 0;
        }
        Matcher m = Pattern.compile("(\\d{3,4})").matcher(s);
        if (!m.find()) {
            return 0;
        }
        try {
            int h = Integer.parseInt(m.group(1));
            return (h >= 144 && h <= 4320) ? h : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
