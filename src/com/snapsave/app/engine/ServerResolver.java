package com.snapsave.app.engine;

import com.snapsave.app.model.StreamOption;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * Resolver backend client.
 *
 * Primary contract (OFFLIN API, off-l.in): POST multipart {@code url=<page>} to the endpoint,
 * get back
 *   { "status":"success", "platform":"youtube",
 *     "videos":["youtube_video_123.mp4"],      // served at <origin>/downloads/<name>
 *     "images":["photo.jpg"] }
 * or a generic direct-link shape:
 *   { "url":"https://.../x.mp4", "audio":"https://.../a.m4a",
 *     "downloads":[{"url":"...","quality":"1080p","format":"mp4"}],
 *     "title":"...", "thumbnail":"..." }
 *
 * Optional API key is sent as {@code Authorization: Bearer <key>}.
 */
public final class ServerResolver {

    private ServerResolver() {
    }

    public static ResolvedMedia resolve(String server, String apiKey, String url) {
        try {
            String resp = Http.postForm(server, apiKey, Http.UA, new String[][]{
                    {"url", url}, {"quality", "highest"}
            });
            JSONObject j = new JSONObject(resp);
            String status = j.optString("status", "");
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status) || j.has("error")) {
                Http.noteFailureText(j.optString("error", "") + " " + j.optString("message", ""));
                return null;
            }

            ResolvedMedia r = new ResolvedMedia();
            r.pageUrl = url;
            r.grad = Math.abs(url.hashCode()) % 4;
            String title = Resolvers.firstText(j.optString("title", null), j.optString("caption", null));
            r.title = title == null ? "" : title;
            r.author = j.optString("author", "");
            r.thumbUrl = j.optString("thumbnail", null);
            r.duration = j.optString("duration", "");

            String origin = origin(server);
            int n = 0;

            // generic direct link + optional separate audio
            String direct = j.optString("url", "");
            if (direct.startsWith("http")) {
                String audio = j.optString("audio", "");
                addVideo(r, direct, j.optString("quality", "Original"),
                        j.optString("mime", "video/mp4"), !audio.startsWith("http"));
                if (audio.startsWith("http")) {
                    addAudio(r, audio);
                }
                n++;
            }

            // picker / downloads array
            JSONArray downloads = j.optJSONArray("downloads");
            if (downloads == null) {
                downloads = j.optJSONArray("picker");
            }
            if (downloads != null) {
                for (int i = 0; i < downloads.length(); i++) {
                    JSONObject d = downloads.optJSONObject(i);
                    if (d == null) continue;
                    String u = d.optString("url", "");
                    if (!u.startsWith("http")) continue;
                    addVideo(r, u, d.optString("quality", "Original"),
                            d.optString("mime", "video/mp4"), true);
                    n++;
                }
            }

            // filename lists (off-l.in style) -> <origin>/downloads/<file>
            JSONArray videos = j.optJSONArray("videos");
            if (videos != null) {
                for (int i = 0; i < videos.length(); i++) {
                    String name = videos.optString(i, "");
                    if (name.isEmpty()) continue;
                    addVideo(r, origin + "/downloads/" + pathEncode(name),
                            videos.length() > 1 ? "Video " + (i + 1) : "Original", "video/mp4", true);
                    n++;
                }
            }
            JSONArray images = j.optJSONArray("images");
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    String name = images.optString(i, "");
                    if (name.isEmpty()) continue;
                    StreamOption o = new StreamOption();
                    o.url = origin + "/downloads/" + pathEncode(name);
                    o.mimeType = "image/jpeg";
                    o.label = images.length() > 1 ? "Image " + (i + 1) : "Image";
                    o.sub = "Photo";
                    o.progressive = true;
                    r.videoStreams.add(o);
                    n++;
                }
            }

            r.progressive = r.bestAudio == null;
            return n > 0 ? r : null;
        } catch (Throwable t) {
            Http.noteFailure(t);
            return null;
        }
    }

    private static void addVideo(ResolvedMedia r, String url, String label, String mime, boolean progressive) {
        StreamOption o = new StreamOption();
        o.url = url;
        o.mimeType = mime;
        o.label = label == null || label.isEmpty() ? "Original" : label;
        o.sub = "Server • " + host(url);
        o.progressive = progressive;
        r.videoStreams.add(o);
    }

    private static void addAudio(ResolvedMedia r, String url) {
        StreamOption a = new StreamOption();
        a.url = url;
        a.mimeType = "audio/mp4";
        a.audioOnly = true;
        a.label = "Audio";
        a.sub = "Server audio";
        a.bitrate = 128;
        r.audioStreams.add(a);
        r.bestAudio = a;
    }

    private static String pathEncode(String name) {
        try {
            return URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return name;
        }
    }

    private static String origin(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort();
            return u.getProtocol() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String host(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "server";
        }
    }
}
