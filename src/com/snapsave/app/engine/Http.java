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
        HttpURLConnection c = open(url, null, UA);
        c.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            c.setRequestProperty("X-Api-Key", apiKey);
        }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Resp(code, readAll(in));
        } finally {
            c.disconnect();
        }
    }

    /** POST JSON returning status + body, with optional X-Api-Key. */
    public static Resp postResp(String url, String json, String apiKey) throws IOException {
        HttpURLConnection c = open(url, null, UA);
        c.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            c.setRequestProperty("X-Api-Key", apiKey);
        }
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try {
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Resp(code, readAll(in));
        } finally {
            c.disconnect();
        }
    }

    /** GET with a single URL-encoded query param + optional X-Api-Key header.
     *  Returns the body even on 4xx (callers inspect an {@code ok} field). */
    public static String getEncoded(String base, String param, String value, String apiKey) throws IOException {
        String u = base + (base.contains("?") ? "&" : "?") + param + "="
                + java.net.URLEncoder.encode(value, "UTF-8");
        HttpURLConnection c = open(u, null, UA);
        c.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            c.setRequestProperty("X-Api-Key", apiKey);
        }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    /** POST JSON with optional X-Api-Key header; body returned even on 4xx. */
    public static String postJsonKey(String url, String json, String apiKey) throws IOException {
        HttpURLConnection c = open(url, null, UA);
        c.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            c.setRequestProperty("X-Api-Key", apiKey);
        }
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try {
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return readAll(in);
        } finally {
            c.disconnect();
        }
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
        HttpURLConnection c = open(url, null, ua);
        c.setConnectTimeout(15000);
        c.setReadTimeout(40000);   // resolver backends can take a while (Instagram etc.)
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        c.setDoOutput(true);
        try {
            OutputStream os = c.getOutputStream();
            StringBuilder sb = new StringBuilder();
            for (String[] f : fields) {
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n");
                sb.append(f[1] == null ? "" : f[1]).append("\r\n");
            }
            sb.append("--").append(boundary).append("--\r\n");
            os.write(sb.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(in);
            if (code >= 400) {
                throw new IOException("HTTP " + code + (body == null ? "" : ": " + body));
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String referer, String ua) throws IOException {
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
            long len = c.getContentLength();
            c.disconnect();
            return len;
        } catch (Exception e) {
            return -1;
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
        HttpURLConnection c = open(url, referer, ua);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);          // a slow mobile stream must not be mistaken for a dead one
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (already > 0) {
            c.setRequestProperty("Range", "bytes=" + already + "-");
        }
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            boolean resume = code == 206 && already > 0;
            long base = resume ? already : 0;
            if (!resume && out.exists() && out.length() > 0) {
                out.delete();             // server ignored the Range header — start clean
            }
            long len = c.getContentLength();
            long total = len > 0 ? base + len : 0;
            InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
            FileOutputStream fos = new FileOutputStream(out, resume);
            try {
                byte[] buf = new byte[65536];
                long done = base;
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

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
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
