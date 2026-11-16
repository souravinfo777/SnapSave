package com.snapsave.app.engine;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal HTTP client (HttpURLConnection) with progress + cancellation for the download engine. */
public final class Http {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    public interface Progress {
        void onProgress(long done, long total);
    }

    /** Thrown when a download is cancelled by the user. */
    public static class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    private Http() {
    }

    public static String get(String url, String referer, String ua) throws IOException {
        HttpURLConnection c = open(url, referer, ua);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(in);
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** HTTP response with status + body (body is returned even for 4xx). */
    public static final class Resp {
        public final int code;
        public final String body;

        public Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** GET returning status + body, with optional X-Api-Key. */
    public static Resp getResp(String url, String apiKey) throws IOException {
        return apiCall(url, "GET", null, null, apiKey, null);
    }

    /** POST JSON returning status + body, with optional X-Api-Key. */
    public static Resp postResp(String url, String json, String apiKey) throws IOException {
        return apiCall(url, "POST", utf8(json), "application/json", apiKey, null);
    }

    /**
     * Request that carries credentials (X-Api-Key / Authorization).
     *
     * {@link HttpURLConnection} follows redirects automatically and re-sends custom headers to
     * the new host, which would leak the API key to whatever server the redirect names. Redirects
     * are therefore followed by hand, only when the target is still HTTPS on the same origin.
     */
    private static Resp apiCall(String url, String method, byte[] body, String contentType,
                                String apiKey, String bearer) throws IOException {
        return apiCall(url, method, body, contentType, apiKey, bearer, 12000, 18000);
    }

    private static Resp apiCall(String url, String method, byte[] body, String contentType,
                                String apiKey, String bearer, int connectMs, int readMs)
            throws IOException {
        String origin = originOf(url);
        String u = url;
        for (int hop = 0; hop < 4; hop++) {
            requireHttps(u);
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (apiKey != null && !apiKey.isEmpty()) {
                c.setRequestProperty("X-Api-Key", apiKey);
            }
            if (bearer != null && !bearer.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            try {
                if (body != null && body.length > 0) {
                    c.setRequestMethod("POST");
                    if (contentType != null) {
                        c.setRequestProperty("Content-Type", contentType);
                    }
                    c.setDoOutput(true);
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.close();
                } else {
                    c.setRequestMethod(method);
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    String next = loc == null ? null : new URL(new URL(u), loc).toString();
                    c.disconnect();
                    if (next == null) {
                        throw new IOException("HTTP " + code);
                    }
                    if (!u.equalsIgnoreCase(next) && !sameOrigin(origin, next)) {
                        throw new IOException("redirect off-origin blocked");
                    }
                    u = next;
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                return new Resp(code, readAll(in));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static boolean sameOrigin(String a, String b) {
        return a != null && a.equalsIgnoreCase(originOf(b));
    }

    private static byte[] utf8(String s) {
        try {
            return (s == null ? "" : s).getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** GET with a single URL-encoded query param + optional X-Api-Key header.
     *  Returns the body even on 4xx (callers inspect an {@code ok} field). */
    public static String getEncoded(String base, String param, String value, String apiKey) throws IOException {
        String u = base + (base.contains("?") ? "&" : "?") + param + "="
                + java.net.URLEncoder.encode(value, "UTF-8");
        return apiCall(u, "GET", null, null, apiKey, null).body;
    }

    /** POST JSON with optional X-Api-Key header; body returned even on 4xx. */
    public static String postJsonKey(String url, String json, String apiKey) throws IOException {
        return apiCall(url, "POST", utf8(json), "application/json", apiKey, null).body;
    }

    public static String postJson(String url, String json, String referer, String ua) throws IOException {
        HttpURLConnection c = open(url, referer, ua);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try {
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(in);
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** multipart/form-data POST with a single field (+ optional fields) — used for resolver APIs. */
    public static String postForm(String url, String bearer, String ua, String[][] fields) throws IOException {
        String boundary = "----SnapSave" + System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (String[] f : fields) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n");
            sb.append(f[1] == null ? "" : f[1]).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        Resp r = apiCall(url, "POST", utf8(sb.toString()),
                "multipart/form-data; boundary=" + boundary, null, bearer, 15000, 40000);
        if (r.code >= 400) {
            throw new IOException("HTTP " + r.code + (r.body == null ? "" : ": " + r.body));
        }
        return r.body;
    }

    /**
     * Every resolver / media URL must be HTTPS. The manifest also denies cleartext traffic,
     * but failing here with a readable message beats an opaque platform exception at write time.
     */
    private static void requireHttps(String url) throws IOException {
        if (url != null && url.regionMatches(true, 0, "https://", 0, 8)) {
            return;
        }
        throw new IOException("insecure (non-https) url: " + url);
    }

    private static HttpURLConnection open(String url, String referer, String ua) throws IOException {
        requireHttps(url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua == null ? UA : ua);
        if (referer != null) {
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Origin", originOf(referer));
        }
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        return c;
    }

    private static String originOf(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public static long contentLength(String url, String referer, String ua) {
        try {
            HttpURLConnection c = open(url, referer, ua);
            c.setRequestMethod("HEAD");
            long len = lengthOf(c);
            c.disconnect();
            return len;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Content-Length as a long; falls back for API < 24 where the long variant is missing. */
    private static long lengthOf(HttpURLConnection c) {
        try {
            return c.getContentLengthLong();
        } catch (Throwable ignored) {
            return c.getContentLength();
        }
    }

    /** Total size from a {@code Content-Range: bytes a-b/total} header, or -1. */
    private static long rangeTotal(String header) {
        if (header == null) {
            return -1;
        }
        int slash = header.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** First byte offset from a {@code Content-Range: bytes a-b/total} header, or -1. */
    private static long rangeStart(String header) {
        if (header == null) {
            return -1;
        }
        int sp = header.indexOf(' ');
        int dash = sp < 0 ? -1 : header.indexOf('-', sp + 1);
        if (dash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(header.substring(sp + 1, dash).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Cut a partial file back to exactly {@code len} bytes (never grows it). */
    private static void truncate(File f, long len) throws IOException {
        if (f == null || len < 0 || !f.exists() || f.length() <= len) {
            return;
        }
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
        try {
            raf.setLength(len);
        } finally {
            raf.close();
        }
    }

    /**
     * Download {@code url} into {@code out}. Reports progress. Throws {@link Cancelled}
     * when {@code token.cancelled} becomes true.
     */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * Download {@code url} into {@code out} with resume + retry.
     *
     * A dropped/aborted socket on a slow mobile network ("Software caused connection abort")
     * must not fail the whole download: partial bytes are kept, a {@code Range: bytes=N-}
     * request continues from there, and transient errors are retried a few times with backoff.
     */
    public static void download(String url, File out, String referer, String ua, Progress p, CancelToken token)
            throws IOException {
        String ref = referer;
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (token != null && token.cancelled) {
                throw new Cancelled();
            }
            long already = out != null && out.exists() ? out.length() : 0;
            try {
                downloadOnce(url, out, ref, ua, p, token, already);
                return;
            } catch (Cancelled c) {
                throw c;
            } catch (IOException e) {
                last = e;
                if (token != null && token.cancelled) {
                    throw new Cancelled();
                }
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.startsWith("insecure")) {
                    throw e;                  // policy violation — retrying cannot help
                }
                if (m.contains("range mismatch")) {
                    // The server answered 206 from an offset we did not request. Discard the
                    // partial so the next attempt starts from byte 0 instead of appending twice.
                    boolean dropped = out != null && (!out.exists() || out.delete());
                    if (dropped && attempt < MAX_ATTEMPTS) {
                        continue;
                    }
                    throw e;
                }
                if (m.contains("416")) {
                    // Our partial is stale or oversized. Drop it and start the part over.
                    boolean dropped = out != null && (!out.exists() || out.delete());
                    if (dropped && attempt < MAX_ATTEMPTS) {
                        continue;
                    }
                    throw e;
                }
                if (m.contains("HTTP 4")) {
                    // Access denied: retry once with the media host as the referer.
                    String alt = originOf(url);
                    if ((m.contains("403") || m.contains("401")) && attempt == 1 && alt != null
                            && (ref == null || !ref.startsWith(alt))) {
                        ref = alt;
                        continue;
                    }
                    throw e;                      // a real client error won't heal itself
                }
                // Transient socket error / timeout: back off and resume from the partial file.
                try {
                    Thread.sleep(700L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new IOException("download failed") : last;
    }

    private static void downloadOnce(String url, File out, String referer, String ua, Progress p,
                                     CancelToken token, long already) throws IOException {
        // Redirects are followed by hand: an https -> http downgrade must fail with a readable
        // error instead of an opaque "CleartextNotPermittedException" halfway through a body.
        String cur = url;
        HttpURLConnection c = null;
        int code = -1;
        for (int hop = 0; hop <= 5; hop++) {
            c = open(cur, referer, ua);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);      // a slow mobile stream must not be mistaken for a dead one
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (already > 0) {
                c.setRequestProperty("Range", "bytes=" + already + "-");
            }
            code = c.getResponseCode();
            if (code < 300 || code >= 400) {
                break;
            }
            String loc = c.getHeaderField("Location");
            String next = loc == null ? null : new URL(new URL(cur), loc).toString();
            c.disconnect();
            c = null;
            if (next == null) {
                throw new IOException("HTTP " + code);
            }
            requireHttps(next);
            cur = next;
        }
        if (c == null) {
            throw new IOException("too many redirects");
        }
        try {
            String cr = c.getHeaderField("Content-Range");
            long total = rangeTotal(cr);
            long start = rangeStart(cr);

            if (code == 416) {
                // "Range Not Satisfiable": the part is either already complete (a stale offset
                // past EOF), oversized (a previous attempt appended past the real end), or
                // genuinely short. Only the first two may be treated as done.
                if (total > 0 && out.exists() && out.length() >= total) {
                    truncate(out, total);
                    if (p != null) {
                        p.onProgress(total, total);
                    }
                    return;
                }
                throw new IOException("HTTP 416");
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }

            boolean resume = code == 206 && already > 0;
            if (resume && start >= 0 && start != already) {
                // The server resumed from a different offset than we asked for. Appending would
                // duplicate bytes; restarting from 0 is safe only when it truly sent byte 0.
                if (start == 0) {
                    resume = false;
                } else {
                    throw new IOException("range mismatch " + start + "/" + already);
                }
            }
            if (resume && total > 0 && already > total) {
                // Our partial already covers the whole file (an earlier over-append).
                truncate(out, total);
                if (p != null) {
                    p.onProgress(total, total);
                }
                return;
            }
            long base = resume ? already : 0;
            if (!resume && out.exists() && out.length() > 0) {
                out.delete();             // server ignored the Range header — start clean
            }
            if (total <= 0) {
                long len = lengthOf(c);
                total = len > 0 ? base + len : 0;
            }
            InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
            FileOutputStream fos = new FileOutputStream(out, resume);
            long done = base;
            try {
                byte[] buf = new byte[65536];
                long lastReport = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (token != null && token.cancelled) {
                        throw new Cancelled();
                    }
                    fos.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (p != null && (now - lastReport > 150)) {
                        lastReport = now;
                        p.onProgress(done, total);
                    }
                }
                // A short body must never be reported as a finished download: the worker would
                // delete the temp and mark the task complete with a truncated file.
                if (total > 0 && done < total) {
                    throw new IOException("incomplete " + done + "/" + total);
                }
                if (total > 0 && done > total) {
                    // The server overshot the advertised length — keep only the real bytes.
                    truncate(out, total);
                    done = total;
                }
                if (p != null) {
                    p.onProgress(done, total > 0 ? total : done);
                }
            } finally {
                try {
                    fos.flush();
                } catch (Exception ignored) {
                }
                fos.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Hard cap on buffered response bodies — a hostile/huge page must not exhaust the heap. */
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while (bos.size() < MAX_BODY && (n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** Simple cooperative cancellation token shared with the worker thread. */
    public static final class CancelToken {
        public volatile boolean cancelled = false;
    }
}
