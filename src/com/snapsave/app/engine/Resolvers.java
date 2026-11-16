package com.snapsave.app.engine;

import com.snapsave.app.model.MediaItem;
import com.snapsave.app.model.StreamOption;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves page URLs into downloadable streams and provides live catalogue feeds.
 *
 * Routing:
 *  - YouTube      -> InnerTube player via the ANDROID_VR client (only client still returning
 *                    un-ciphered URLs); adaptive tracks are muxed by the native engine.
 *  - TikTok       -> public resolver API, then page scrape.
 *  - Instagram    -> page scrape (og:video / video_url / video_versions), then __a=1.
 *  - Facebook     -> page scrape (playable_url / *_src / og:video).
 *  - X / Twitter  -> syndication endpoint, then og:video.
 *  - Anything else-> generic scrape (og:video, <video>/<source>, raw .mp4/.m3u8 links).
 *  - Direct media -> passed through.
 *
 * Note: TikTok/Instagram/Facebook deliberately block non-authenticated scraping, so those paths
 * are best-effort; failures degrade to a clear empty result rather than fake data.
 */
public final class Resolvers {

    private static final String KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
    private static final String VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US; Quest 3 Build/SQ3A.220605.009.A1) gzip";
    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    /**
     * The UA the InnerTube player is queried with. googlevideo URLs are bound to that client,
     * so the download request must use the same one or the server answers 403.
     */
    public static final String YT_MEDIA_UA = VR_UA;

    private static final Pattern URL_RE =
            Pattern.compile("https?://[^\\s\"'<>\\\\]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_RE =
            Pattern.compile("https?://[^\\s\"'<>\\\\]+\\.(?:mp4|m4a|mp3|webm|m3u8|mov)(?:\\?[^\\s\"'<>\\\\]*)?",
                    Pattern.CASE_INSENSITIVE);

    private Resolvers() {
    }

    // ---------------------------------------------------------------- routing

    public static boolean isYouTube(String url) {
        if (url == null) {
            return false;
        }
        String h = hostOrNull(url);
        if (h == null) {
            // Bare 11-character id (search results / detail screens).
            return url.matches("[A-Za-z0-9_-]{11}");
        }
        String host = h.toLowerCase();
        boolean youtubeHost = host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtu.be") || host.endsWith(".youtu.be")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
        return youtubeHost && videoId(url) != null;
    }

    /**
     * True when the link points at a platform with a built-in extractor (or is a direct media
     * file). Used to ignore unrelated clipboard URLs instead of firing a resolver request.
     */
    public static boolean isSupportedLink(String url) {
        if (url == null) {
            return false;
        }
        if (looksDirect(url) || isYouTube(url)) {
            return true;
        }
        String h = hostOrNull(url);
        if (h == null) {
            return false;
        }
        String host = h.toLowerCase();
        return host.contains("facebook") || host.contains("fb.watch")
                || host.contains("instagram") || host.contains("tiktok")
                || host.contains("twitter") || host.contains("x.com")
                || host.contains("youtube") || host.contains("youtu.be")
                || host.contains("vimeo") || host.contains("dailymotion")
                || host.contains("twitch") || host.contains("pinterest");
    }

    /** Host of a URL, or null when it is not a parseable absolute URL. */
    private static String hostOrNull(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public static String videoId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?:v=|/videos/|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return url.matches("[A-Za-z0-9_-]{11}") ? url : null;
    }

    /**
     * True for URLs that are a single downloadable media file. HLS playlists (.m3u8) and
     * segments (.ts) are deliberately excluded: saving a playlist as ".mp4" produces a file no
     * player can open, and segment fetching is not implemented.
     */
    public static boolean looksDirect(String url) {
        return url != null && url.toLowerCase()
                .matches(".*\\.(mp4|m4a|mp3|webm|mkv|mov|3gp|aac|opus|flac|wav)(\\?.*)?$");
    }

    public static String firstUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL_RE.matcher(text);
        return m.find() ? m.group() : null;
    }

    public static ResolvedMedia resolve(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        if (url.startsWith("http://")) {
            // Cleartext is denied by the manifest and network security config, so an http://
            // request fails before a byte is sent. Nearly every site serves the same page over
            // TLS, so upgrade instead of failing with an opaque platform error.
            url = "https://" + url.substring(7);
        }
        if (looksDirect(url)) {
            return resolveDirect(url);
        }

        String h = host(url).toLowerCase();

        // YouTube: FastSaver returns metadata + every format in one fast call.
        if (isYouTube(url)) {
            if (FastSaver.keyCount() > 0) {
                ResolvedMedia fs = FastSaver.info(url);
                if (fs != null && fs.hasAnything()) {
                    enrich(fs, url);
                    return fs;
                }
            }
            return resolveYouTube(videoId(url), url);
        }

        ResolvedMedia result = null;
        final long start = System.currentTimeMillis();

        // Platforms with a purpose-built extractor run FIRST, so we return every quality
        // (and a real audio track) instead of one generic "Original" from a slow backend.
        if (h.contains("tiktok")) {
            result = tiktokApi(url);
            if (rich(result)) {
                return finish(result, url);
            }
        }

        // Total resolve is bounded: a slow/blocked backend must not keep the "Preparing
        // download" spinner alive for a minute. Each step is skipped once the budget is spent.
        if (FastSaver.keyCount() > 0 && within(start, 15000)) {
            ResolvedMedia fs = FastSaver.fetch(url);
            if (fs != null && fs.hasAnything()) {
                if (rich(fs)) {
                    return finish(fs, url);      // video + audio: nothing better is coming
                }
                // Video-only: keep looking. A later candidate with a separate audio track
                // lets the sheet offer a real MP3 instead of a silent mux.
                result = prefer(result, fs);
            }
        }

        String server = com.snapsave.app.util.Prefs.resolver();
        if (server != null && !server.isEmpty() && within(start, 25000)) {
            ResolvedMedia viaServer = ServerResolver.resolve(server, com.snapsave.app.util.Prefs.apiKey(), url);
            if (rich(viaServer)) {
                return finish(viaServer, url);
            }
            result = prefer(result, viaServer);
        }

        if (within(start, 28000)) {
            ResolvedMedia scraped = scrape(url);
            if (rich(scraped)) {
                return finish(scraped, url);
            }
            result = prefer(result, scraped);
        }

        if (result != null && result.hasAnything()) {
            return finish(result, url);
        }
        // Nothing usable was found. Never fall back to the *page* URL as a "direct" media file:
        // that produced an HTML document named ".mp4" that no player could open.
        return null;
    }

    private static boolean within(long start, long budgetMs) {
        return System.currentTimeMillis() - start < budgetMs;
    }

    /** A result is "rich" when it offers video plus a separate audio track (so the sheet can
     *  show both mp4 qualities and an mp3 option). */
    private static boolean rich(ResolvedMedia r) {
        return r != null && !r.videoStreams.isEmpty() && !r.audioStreams.isEmpty();
    }

    /** Keep whichever of two candidates carries more downloadable options. */
    private static ResolvedMedia prefer(ResolvedMedia a, ResolvedMedia b) {
        if (a == null) return b;
        if (b == null) return a;
        int sa = a.videoStreams.size() + a.audioStreams.size();
        int sb = b.videoStreams.size() + b.audioStreams.size();
        return sb > sa ? b : a;
    }

    private static ResolvedMedia finish(ResolvedMedia r, String url) {
        enrich(r, url);
        return r;
    }

    // ---------------------------------------------------------------- metadata enrichment

    /** Fill in a nicer title / thumbnail when the backend returns only file names. */
    private static void enrich(ResolvedMedia r, String url) {
        String current = firstText(r.title);
        r.title = current == null ? "" : current;
        // Re-fetch the page when the backend gave us nothing usable: no title, a raw URL, a
        // file name, or the literal "null" that org.json returns for {"title": null}.
        final boolean needTitle = current == null || "untitled".equalsIgnoreCase(current)
                || current.startsWith("http") || current.toLowerCase().endsWith(".mp4");
        final boolean needThumb = r.thumbUrl == null || r.thumbUrl.isEmpty();
        final boolean needAuthor = r.author == null || r.author.isEmpty();
        if (!needTitle && !needThumb && !needAuthor) {
            return;   // metadata is already complete — don't spend seconds re-fetching the page
        }
        try {
            if (isYouTube(url)) {
                String oe = Http.get("https://www.youtube.com/oembed?format=json&url="
                        + URLEncoder.encode(url, "UTF-8"), null, Http.UA);
                JSONObject o = new JSONObject(oe);
                if (needTitle && o.has("title")) r.title = o.getString("title");
                if (needAuthor && o.has("author_name")) r.author = o.getString("author_name");
                if (needThumb && o.has("thumbnail_url")) r.thumbUrl = o.getString("thumbnail_url");
            } else {
                String html = Http.get(url, null, MOBILE_UA);
                if (html != null && !html.isEmpty()) {
                    if (needTitle) {
                        String t = nonEmpty(meta(html, "og:title"), tagTitle(html));
                        if (t != null) r.title = t;
                    }
                    if (needThumb) r.thumbUrl = meta(html, "og:image");
                    if (needAuthor) r.author = meta(html, "og:site_name");
                }
            }
        } catch (Throwable ignored) {
        }
        // Platforms that block scraping (Facebook, Instagram) give us no title at all; a
        // readable platform name beats showing the raw link in the sheet and the file name.
        if (firstText(r.title) == null) {
            r.title = platformTitle(url);
        }
    }

    /** Best-effort display name when a platform exposes no title of its own. */
    private static String platformTitle(String url) {
        String h = host(url).toLowerCase();
        if (h.contains("facebook") || h.contains("fb.watch") || h.contains("fbcdn")) return "Facebook video";
        if (h.contains("instagram")) return "Instagram video";
        if (h.contains("tiktok")) return "TikTok video";
        if (h.contains("twitter") || h.contains("x.com")) return "X video";
        if (h.contains("youtu")) return "YouTube video";
        return "Downloaded video";
    }

    // ---------------------------------------------------------------- direct

    private static ResolvedMedia resolveDirect(String url) {
        ResolvedMedia r = new ResolvedMedia();
        r.pageUrl = url;
        r.progressive = true;
        r.directUrl = url;
        r.directMime = guessMime(url);
        String name = fileName(url);
        r.title = name.isEmpty() ? "Media file" : bareName(name);
        r.grad = Math.abs(url.hashCode()) % 4;
        StreamOption opt = new StreamOption("Original", "Direct link • " + host(url), 0, 0,
                r.directMime.startsWith("audio"));
        opt.url = url;
        opt.mimeType = r.directMime;
        opt.progressive = true;
        if (r.directMime.startsWith("audio")) {
            r.audioStreams.add(opt);
        } else {
            r.videoStreams.add(opt);
        }
        return r;
    }

    // ---------------------------------------------------------------- TikTok API

    private static ResolvedMedia tiktokApi(String url) {
        try {
            String api = "https://www.tikwm.com/api/?hd=1&url=" + URLEncoder.encode(url, "UTF-8");
            String resp = Http.get(api, "https://www.tikwm.com/", WEB_UA);
            JSONObject j = new JSONObject(resp);
            if (j.optInt("code", -1) != 0) {
                return null;
            }
            JSONObject d = j.optJSONObject("data");
            if (d == null) {
                return null;
            }
            ResolvedMedia r = new ResolvedMedia();
            r.pageUrl = url;
            r.progressive = true;
            r.grad = Math.abs(url.hashCode()) % 4;
            r.title = nonEmpty(d.optString("title", null), "TikTok video");
            JSONObject au = d.optJSONObject("author");
            r.author = au != null ? nonEmpty(au.optString("nickname", null), "TikTok") : "TikTok";
            r.thumbUrl = nonEmpty(d.optString("cover", null), null);
            r.duration = d.optInt("duration", 0) > 0
                    ? String.format("%d:%02d", d.optInt("duration") / 60, d.optInt("duration") % 60) : "";
            String hd = d.optString("hdplay", null);
            String sd = d.optString("play", null);
            addProgressive(r, hd, "MP4 • No watermark (HD)", 1080);
            if (sd != null && !sd.equals(hd)) {
                addProgressive(r, sd, "MP4 • No watermark", 720);
            }
            addAudio(r, d.optString("music", null), "MP3 • Original sound");
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- generic multi-platform scrape

    private static final String[] JSON_KEYS = {
            "playAddr", "downloadAddr", "video_url", "playable_url_quality_hd", "playable_url",
            "browser_native_hd_url", "browser_native_sd_url", "hd_src", "sd_src", "contentUrl"
    };

    private static ResolvedMedia scrape(String url) {
        try {
            String html = Http.get(url, null, MOBILE_UA);
            if (html == null || html.isEmpty()) {
                return null;
            }
            ResolvedMedia r = new ResolvedMedia();
            r.pageUrl = url;
            r.grad = Math.abs(url.hashCode()) % 4;
            r.title = nonEmpty(meta(html, "og:title"), nonEmpty(tagTitle(html), host(url)));
            r.author = nonEmpty(meta(html, "og:site_name"), host(url));
            r.thumbUrl = meta(html, "og:image");

            List<String> found = new ArrayList<String>();
            add(found, meta(html, "og:video:secure_url"));
            add(found, meta(html, "og:video:url"));
            add(found, meta(html, "og:video"));
            add(found, meta(html, "twitter:player:stream"));
            for (String k : JSON_KEYS) {
                Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*\"(https?:[^\"]+?)\"").matcher(html);
                while (m.find()) {
                    add(found, unescape(m.group(1)));
                }
            }
            Matcher src = Pattern.compile("<(?:video|source)[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (src.find()) {
                add(found, unescape(src.group(1)));
            }
            Matcher raw = MEDIA_RE.matcher(html);
            while (raw.find()) {
                add(found, raw.group());
            }

            List<String> uniq = new ArrayList<String>();
            for (String f : found) {
                String abs = f.startsWith("//") ? "https:" + f : f;
                if (abs.startsWith("http") && !uniq.contains(abs) && !isPlayerPage(abs)) {
                    uniq.add(abs);
                }
            }
            Collections.sort(uniq, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return rank(a) - rank(b);
                }
            });

            for (String u : uniq) {
                String mime = guessMime(u);
                StreamOption o = new StreamOption();
                o.url = u;
                o.mimeType = mime;
                o.progressive = true;
                o.sub = host(u);
                if (mime.startsWith("audio")) {
                    o.audioOnly = true;
                    o.label = "Audio";
                    r.audioStreams.add(o);
                } else if (u.toLowerCase().contains(".m3u8")) {
                    o.label = "HLS stream";
                    r.videoStreams.add(o);
                } else {
                    o.label = "Original";
                    r.videoStreams.add(o);
                }
            }
            r.progressive = true;
            return r.hasAnything() ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void add(List<String> out, String u) {
        if (u == null || u.isEmpty()) {
            return;
        }
        String s = u.toLowerCase();
        if (s.contains(".m3u8")) {
            return;                       // HLS playlist — not a single downloadable file
        }
        if (s.startsWith("http") && (s.contains(".mp4") || s.contains(".webm")
                || s.contains(".m4a") || s.contains(".mp3") || s.contains("videoplayback") || s.contains("video"))) {
            if (!out.contains(u)) {
                out.add(u);
            }
        }
    }

    private static boolean isPlayerPage(String u) {
        String s = u.toLowerCase();
        return s.contains("player.vimeo.com") || s.contains("/embed/") || s.contains("youtube.com/embed");
    }

    private static int rank(String u) {
        String s = u.toLowerCase();
        if (s.contains(".mp4")) return 0;
        if (s.contains(".m4a") || s.contains(".mp3")) return 1;
        if (s.contains(".webm")) return 2;
        if (s.contains(".m3u8")) return 3;
        return 4;
    }

    private static void addProgressive(ResolvedMedia r, String url, String label, int height) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (url.startsWith("//")) {
            url = "https:" + url;
        } else if (url.startsWith("/")) {
            url = "https://www.tikwm.com" + url;
        }
        StreamOption o = new StreamOption();
        o.url = url;
        o.mimeType = guessMime(url);
        o.label = label;
        o.sub = host(url);
        o.height = height;
        o.progressive = true;
        r.videoStreams.add(o);
    }

    private static void addAudio(ResolvedMedia r, String url, String label) {
        if (url == null || url.isEmpty()) {
            return;
        }
        StreamOption o = new StreamOption();
        o.url = url;
        o.mimeType = "audio/mpeg";
        o.label = label;
        o.sub = "Audio";
        o.audioOnly = true;
        o.bitrate = 128;
        r.audioStreams.add(o);
    }

    // ---------------------------------------------------------------- meta helpers

    private static String meta(String html, String property) {
        Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(property)
                + "[\"'][^>]*\\bcontent=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']"
                + Pattern.quote(property) + "[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String tagTitle(String html) {
        Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String unescape(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\/", "/").replace("\\u0026", "&").replace("\\u003D", "=")
                .replace("\\u003F", "?").replace("\\u0025", "%");
    }

    private static String fileName(String url) {
        try {
            String n = url.substring(url.lastIndexOf('/') + 1);
            int q = n.indexOf('?');
            return q >= 0 ? n.substring(0, q) : n;
        } catch (Exception e) {
            return "";
        }
    }

    /** File name without its extension — used as a display title for direct links. */
    private static String bareName(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String host(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "link";
        }
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase();
        if (u.contains(".mp3")) return "audio/mpeg";
        if (u.contains(".m4a") || u.contains(".aac")) return "audio/mp4";
        if (u.contains(".opus")) return "audio/opus";
        if (u.contains(".flac")) return "audio/flac";
        if (u.contains(".wav")) return "audio/wav";
        if (u.contains(".ogg") || u.contains(".oga")) return "audio/ogg";
        if (u.contains(".webm")) return "video/webm";
        if (u.contains(".mkv")) return "video/x-matroska";
        if (u.contains(".mov")) return "video/quicktime";
        if (u.contains(".png")) return "image/png";
        if (u.contains(".jpg") || u.contains(".jpeg")) return "image/jpeg";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        return "video/mp4";
    }

    /**
     * First candidate that is real text: skips null, blanks, and the literal "null"/"undefined"
     * that Android's org.json optString() yields for an explicit JSON null (e.g. {"title": null}).
     */
    public static String firstText(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String c : candidates) {
            if (c != null) {
                String t = c.trim();
                if (!t.isEmpty() && !"null".equalsIgnoreCase(t) && !"undefined".equalsIgnoreCase(t)) {
                    return t;
                }
            }
        }
        return null;
    }

    private static String nonEmpty(String a, String b) {
        return firstText(a, b);
    }

    /**
     * Resolution hinted by a media URL, e.g. "...tag=dash_h264-basic-gen2_720p" -> 720.
     * Backends that omit {@code height} usually still stamp the rendition into the URL.
     */
    public static int heightFromUrl(String url) {
        if (url == null) {
            return 0;
        }
        Matcher m = Pattern.compile("(?<![0-9])(\\d{3,4})p(?![0-9])", Pattern.CASE_INSENSITIVE).matcher(url);
        int best = 0;
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            if (h >= 144 && h <= 4320 && h > best) {
                best = h;
            }
        }
        return best;
    }

    /** Bits-per-second advertised in a media URL ("&bitrate=806607"), or 0 when absent. */
    public static long bitrateFromUrl(String url) {
        if (url == null) {
            return 0;
        }
        Matcher m = Pattern.compile("[?&]bitrate=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * True byte size of a direct media URL, following redirects with a bounded HEAD chain
     * (CDNs answer the first hop with a 302 that carries no length). -1 when unknown.
     */
    public static long probeSize(String url) {
        try {
            String u = url;
            for (int hop = 0; hop < 4 && u != null; hop++) {
                HttpURLConnection c = (HttpURLConnection) new java.net.URL(u).openConnection();
                c.setRequestMethod("HEAD");
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", MOBILE_UA);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    u = loc == null ? null : new java.net.URL(new java.net.URL(u), loc).toString();
                    continue;
                }
                long len;
                try {
                    len = c.getContentLengthLong();
                } catch (Throwable legacy) {
                    len = c.getContentLength();     // API < 24
                }
                c.disconnect();
                return len > 0 ? len : -1;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ---------------------------------------------------------------- YouTube

    private static ResolvedMedia resolveYouTube(String videoId, String pageUrl) {
        ResolvedMedia r = new ResolvedMedia();
        r.videoId = videoId;
        r.pageUrl = "https://www.youtube.com/watch?v=" + videoId;
        r.grad = Math.abs(videoId.hashCode()) % 4;
        r.title = "YouTube video";
        r.author = "YouTube";
        r.thumbUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        try {
            String oe = Http.get("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, "UTF-8"), null, Http.UA);
            JSONObject o = new JSONObject(oe);
            if (o.has("title")) r.title = o.getString("title");
            if (o.has("author_name")) r.author = o.getString("author_name");
        } catch (Exception ignored) {
        }

        try {
            JSONObject body = new JSONObject();
            body.put("context", vrContext());
            body.put("videoId", videoId);
            body.put("contentCheckOk", true);
            body.put("racyCheckOk", true);
            String resp = Http.postJson("https://www.youtube.com/youtubei/v1/player?key=" + KEY,
                    body.toString(), "https://www.youtube.com", VR_UA);
            JSONObject json = new JSONObject(resp);

            JSONObject details = json.optJSONObject("videoDetails");
            if (details != null) {
                if (details.has("title")) r.title = details.getString("title");
                if (details.has("author")) r.author = details.getString("author");
                int len = details.optInt("lengthSeconds", 0);
                if (len > 0) r.duration = String.format("%d:%02d", len / 60, len % 60);
            }

            JSONObject sd = json.optJSONObject("streamingData");
            if (sd != null) {
                JSONArray formats = sd.optJSONArray("formats");
                if (formats != null) {
                    for (int i = 0; i < formats.length(); i++) {
                        JSONObject f = formats.getJSONObject(i);
                        String url = f.optString("url", null);
                        if (url == null || url.isEmpty()) continue;
                        StreamOption opt = new StreamOption();
                        opt.url = url;
                        opt.mimeType = f.optString("mimeType", "video/mp4");
                        opt.itag = f.optInt("itag");
                        opt.sizeBytes = estimateSize(f);
                        int h = f.optInt("height", 0);
                        opt.height = h;
                        opt.label = h > 0 ? h + "p" : "Progressive";
                        opt.sub = "MP4 • With audio";
                        opt.progressive = true;
                        r.videoStreams.add(opt);
                    }
                }
                JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
                if (adaptive != null) {
                    for (int i = 0; i < adaptive.length(); i++) {
                        JSONObject f = adaptive.getJSONObject(i);
                        String url = f.optString("url", null);
                        if (url == null || url.isEmpty()) continue;
                        String mime = f.optString("mimeType", "");
                        StreamOption opt = new StreamOption();
                        opt.url = url;
                        opt.mimeType = mime;
                        opt.itag = f.optInt("itag");
                        opt.sizeBytes = estimateSize(f);
                        if (mime.startsWith("audio")) {
                            opt.audioOnly = true;
                            int br = f.optInt("bitrate", 128000) / 1000;
                            opt.bitrate = br;
                            opt.label = "M4A • " + br + " kbps";
                            opt.sub = "Audio track";
                            r.audioStreams.add(opt);
                        } else {
                            int h = f.optInt("height", 0);
                            opt.height = h;
                            opt.label = h > 0 ? h + "p" : "Video";
                            opt.sub = "MP4 • Video only";
                            r.videoStreams.add(opt);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (!r.audioStreams.isEmpty()) {
            List<StreamOption> a = new ArrayList<StreamOption>(r.audioStreams);
            Collections.sort(a, new Comparator<StreamOption>() {
                @Override
                public int compare(StreamOption x, StreamOption y) {
                    return y.bitrate - x.bitrate;
                }
            });
            r.bestAudio = a.get(0);
        }
        Collections.sort(r.videoStreams, new Comparator<StreamOption>() {
            @Override
            public int compare(StreamOption x, StreamOption y) {
                return y.height - x.height;
            }
        });
        return r;
    }

    // ---------------------------------------------------------------- catalogue (live)

    public static List<MediaItem> search(String query) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        if (query == null || query.trim().isEmpty()) {
            return out;
        }
        if (FastSaver.keyCount() > 0) {
            List<MediaItem> fs = FastSaver.search(query.trim(), 1);
            if (!fs.isEmpty()) {
                return fs;
            }
        }
        try {
            JSONObject body = new JSONObject();
            body.put("context", webContext());
            body.put("query", query.trim());
            String resp = Http.postJson("https://www.youtube.com/youtubei/v1/search?key=" + KEY,
                    body.toString(), "https://www.youtube.com", WEB_UA);
            collect(new JSONObject(resp), out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static List<MediaItem> trending() {
        List<MediaItem> out = new ArrayList<MediaItem>();
        String[] queries = {"music", "trending now"};
        for (String q : queries) {
            for (MediaItem m : search(q)) {
                boolean dup = false;
                for (MediaItem e : out) {
                    if (e.id != null && e.id.equals(m.id)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.add(m);
                }
            }
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    private static JSONObject vrContext() throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "ANDROID_VR");
        client.put("clientVersion", "1.60.19");
        client.put("deviceMake", "Oculus");
        client.put("deviceModel", "Quest 3");
        client.put("androidSdkVersion", 32);
        client.put("osName", "Android");
        client.put("osVersion", "12");
        client.put("hl", "en");
        client.put("gl", "US");
        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }

    private static JSONObject webContext() throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", "2.20240726.00.00");
        client.put("hl", "en");
        client.put("gl", "US");
        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }

    private static long estimateSize(JSONObject f) {
        long len = f.optLong("contentLength", 0);
        if (len > 0) {
            return len;
        }
        long bitrate = f.optLong("bitrate", 0);
        long ms = f.optLong("approxDurationMs", 0);
        if (bitrate > 0 && ms > 0) {
            return bitrate * ms / 1000 / 8;
        }
        return 0;
    }

    private static void collect(Object node, List<MediaItem> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.opt(k);
                if (("videoRenderer".equals(k) || "compactVideoRenderer".equals(k)
                        || "gridVideoRenderer".equals(k) || "reelItemRenderer".equals(k))
                        && v instanceof JSONObject) {
                    MediaItem m = fromRenderer((JSONObject) v);
                    if (m != null) {
                        out.add(m);
                    }
                } else {
                    collect(v, out);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                collect(a.opt(i), out);
            }
        }
    }

    private static MediaItem fromRenderer(JSONObject o) {
        String id = o.optString("videoId", null);
        if (id == null || id.isEmpty()) {
            return null;
        }
        String title = runs(o.optJSONObject("title"));
        if (title == null || title.isEmpty()) {
            return null;
        }
        MediaItem m = new MediaItem();
        m.id = id;
        m.videoId = id;
        m.title = title;
        m.author = orEmpty(firstRuns(o, "ownerText", "longBylineText", "shortBylineText"));
        m.duration = orEmpty(simple(o.optJSONObject("lengthText")));
        m.meta = orEmpty(firstSimple(o, "viewCountText", "shortViewCountText"));
        m.pageUrl = "https://www.youtube.com/watch?v=" + id;
        m.grad = Math.abs(id.hashCode()) % 4;
        m.thumbUrl = thumbOf(o);
        return m;
    }

    private static String thumbOf(JSONObject o) {
        JSONObject th = o.optJSONObject("thumbnail");
        if (th != null) {
            JSONArray arr = th.optJSONArray("thumbnails");
            if (arr != null && arr.length() > 0) {
                return arr.optJSONObject(arr.length() - 1).optString("url", null);
            }
        }
        return "https://i.ytimg.com/vi/" + o.optString("videoId", "") + "/hqdefault.jpg";
    }

    private static String runs(JSONObject o) {
        if (o == null) return null;
        JSONArray runs = o.optJSONArray("runs");
        if (runs != null && runs.length() > 0) {
            return runs.optJSONObject(0).optString("text", null);
        }
        return o.optString("simpleText", null);
    }

    private static String simple(JSONObject o) {
        return o == null ? null : o.optString("simpleText", null);
    }

    private static String firstRuns(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = runs(o.optJSONObject(k));
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String firstSimple(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = simple(o.optJSONObject(k));
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
