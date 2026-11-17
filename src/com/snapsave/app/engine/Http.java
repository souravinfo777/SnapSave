package com.snapsave.app.engine;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    /** A response failure that preserves status without retaining response secrets. */
    public static class HttpException extends IOException {
        public final int status;

        public HttpException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    /** Internal timeout marker used when the resolver request budget expires. */
    public static class Timeout extends SocketTimeoutException {
        public Timeout() {
            super("resolver timeout");
        }
    }

    /** Cooperative request scope used by resolver calls on the current thread. */
    public static final class RequestScope implements AutoCloseable {
        private final long deadlineNanos;
        private final RequestScope previous;
        private volatile boolean cancelled;
        private boolean closed;

        private RequestScope(long budgetMs, RequestScope previous) {
            this.previous = previous;
            long duration = Math.min(Math.max(0L, budgetMs), Integer.MAX_VALUE) * 1000000L;
            if (previous != null) {
                duration = Math.min(duration, Math.max(0L, previous.deadlineNanos - System.nanoTime()));
            }
            this.deadlineNanos = System.nanoTime() + duration;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean cancelled() {
            return cancelled || Thread.currentThread().isInterrupted()
                    || (previous != null && previous.cancelled());
        }

        public long remainingMs() {
            if (cancelled()) {
                return 0;
            }
            long remaining = deadlineNanos - System.nanoTime();
            return remaining > 0 ? Math.max(1L, remaining / 1000000L) : 0;
        }

        public void check() throws IOException {
            if (cancelled()) {
                throw new Cancelled();
            }
            if (remainingMs() <= 0) {
                throw new Timeout();
            }
        }

        private ResolveFailure.Code failureCode = ResolveFailure.Code.NONE;

        private void noteFailure(ResolveFailure.Code code) {
            failureCode = ResolveFailure.stronger(failureCode, code);
        }

        private ResolveFailure.Code failureCode() {
            return failureCode;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (CURRENT_SCOPE.get() == this) {
                    if (previous == null) {
                        CURRENT_SCOPE.remove();
                    } else {
                        CURRENT_SCOPE.set(previous);
                    }
                }
            }
        }
    }

    private static final ThreadLocal<RequestScope> CURRENT_SCOPE = new ThreadLocal<RequestScope>();

    /** Open a resolver scope with a monotonic overall deadline. Close it on the same thread. */
    public static RequestScope openRequestScope(long budgetMs) {
        RequestScope scope = new RequestScope(budgetMs, CURRENT_SCOPE.get());
        CURRENT_SCOPE.set(scope);
        return scope;
    }

    public static RequestScope requestScope() {
        return CURRENT_SCOPE.get();
    }

    static void checkRequestScope() throws IOException {
        RequestScope scope = CURRENT_SCOPE.get();
        if (scope != null) {
            scope.check();
        }
    }

    static boolean requestExpired() {
        RequestScope scope = CURRENT_SCOPE.get();
        return scope != null && scope.remainingMs() <= 0;
    }

    static void noteFailure(Throwable error) {
        RequestScope scope = CURRENT_SCOPE.get();
        if (scope != null) {
            scope.noteFailure(ResolveFailure.codeFor(error));
        }
    }

    static void noteFailureStatus(int status) {
        RequestScope scope = CURRENT_SCOPE.get();
        if (scope != null) {
            scope.noteFailure(ResolveFailure.codeForStatus(status));
        }
    }

    static void noteFailureText(String text) {
        RequestScope scope = CURRENT_SCOPE.get();
        if (scope != null) {
            scope.noteFailure(ResolveFailure.codeForText(text));
        }
    }

    static ResolveFailure.Code failureCode() {
        RequestScope scope = CURRENT_SCOPE.get();
        return scope == null ? ResolveFailure.Code.NONE : scope.failureCode();
    }

    private static int boundedTimeout(int configuredMs) throws IOException {
        RequestScope scope = CURRENT_SCOPE.get();
        if (scope == null) {
            return configuredMs;
        }
        long remaining = scope.remainingMs();
        if (remaining <= 0) {
            scope.check();
        }
        return (int) Math.max(1L, Math.min((long) configuredMs, remaining));
    }

    private Http() {
    }

    public static String get(String url, String referer, String ua) throws IOException {
        HttpURLConnection c = open(url, referer, ua);
        try {
            checkRequestScope();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(in, c);
            if (code >= 400) {
                noteFailureStatus(code);
                throw new HttpException(code);
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
            checkRequestScope();
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(boundedTimeout(connectMs));
            c.setReadTimeout(boundedTimeout(readMs));
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
                checkRequestScope();
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    String next = loc == null ? null : new URL(new URL(u), loc).toString();
                    c.disconnect();
                    if (next == null) {
                        noteFailureStatus(code);
                        throw new HttpException(code);
                    }
                    if (!u.equalsIgnoreCase(next) && !sameOrigin(origin, next)) {
                        throw new IOException("redirect off-origin blocked");
                    }
                    u = next;
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String responseBody = readAll(in, c);
                if (code >= 400) {
                    noteFailureStatus(code);
                }
                return new Resp(code, responseBody);
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
            checkRequestScope();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(in, c);
            if (code >= 400) {
                noteFailureStatus(code);
                throw new HttpException(code);
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
            noteFailureStatus(r.code);
            throw new HttpException(r.code);
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
        checkRequestScope();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(boundedTimeout(12000));
        c.setReadTimeout(boundedTimeout(18000));
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
            String protocol = u.getProtocol().toLowerCase(java.util.Locale.US);
            String host = u.getHost().toLowerCase(java.util.Locale.US);
            int port = u.getPort();
            if (port < 0) {
                port = u.getDefaultPort();
            }
            boolean defaultPort = ("https".equals(protocol) && port == 443)
                    || ("http".equals(protocol) && port == 80);
            return protocol + "://" + host + (port > 0 && !defaultPort ? ":" + port : "");
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
     * when {@code token.stopped()} becomes true.
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
        download(url, out, referer, ua, p, token, 1);
    }

    /**
     * Download with optional parallel range connections. {@code connections > 1} enables a
     * segmented transfer for new files when the server supports byte ranges; resumed partials
     * and range-less servers automatically fall back to the proven single-stream path.
     */
    public static void download(String url, File out, String referer, String ua, Progress p,
                                CancelToken token, int connections) throws IOException {
        String ref = referer;
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkRequestScope();
            if (Thread.currentThread().isInterrupted() || (token != null && token.stopped())) {
                throw new Cancelled();
            }
            long already = out != null && out.exists() ? out.length() : 0;
            try {
                downloadOnce(url, out, ref, ua, p, token, already, connections);
                return;
            } catch (Cancelled c) {
                throw c;
            } catch (IOException e) {
                last = e;
                if (token != null && token.stopped()) {
                    throw new Cancelled();
                }
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.startsWith("insecure")) {
                    throw e;                  // policy violation — retrying cannot help
                }
                if (m.contains("range mismatch") || m.contains("416")) {
                    // The server answered 206 from an offset we did not request, or our
                    // partial is stale. Discard every partial so the next attempt starts
                    // from byte 0 instead of appending twice.
                    boolean dropped = out != null;
                    if (dropped) {
                        deletePartial(out);
                    }
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
                long pause = 700L * attempt;
                while (pause > 0) {
                    checkRequestScope();
                    if (token != null && token.stopped()) {
                        throw new Cancelled();
                    }
                    long slice = Math.min(100L, pause);
                    try {
                        Thread.sleep(slice);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Cancelled();
                    }
                    pause -= slice;
                }
                checkRequestScope();
            }
        }
        throw last == null ? new IOException("download failed") : last;
    }

    private static void downloadOnce(String url, File out, String referer, String ua, Progress p,
                                     CancelToken token, long already, int connections) throws IOException {
        if (connections > 1 && already <= 0 && out != null
                && downloadSegmented(url, out, referer, ua, p, token, connections)) {
            return;
        }
        // Redirects are followed by hand: an https -> http downgrade must fail with a readable
        // error instead of an opaque "CleartextNotPermittedException" halfway through a body.
        String cur = url;
        HttpURLConnection c = null;
        int code = -1;
        for (int hop = 0; hop <= 5; hop++) {
            checkRequestScope();
            c = open(cur, referer, ua);
            c.setConnectTimeout(boundedTimeout(20000));
            c.setReadTimeout(boundedTimeout(60000));      // a slow mobile stream must not be mistaken for a dead one
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
                throw new HttpException(code);
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
                throw new HttpException(416);
            }
            if (code >= 400) {
                throw new HttpException(code);
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
                while (true) {
                    checkRequestScope();
                    if (Thread.currentThread().isInterrupted() || (token != null && token.stopped())) {
                        throw new Cancelled();
                    }
                    c.setReadTimeout(boundedTimeout(60000));
                    n = in.read(buf);
                    checkRequestScope();
                    if (n <= 0) {
                        break;
                    }
                    if (token != null && token.stopped()) {
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

    // ------------------------------------------------------------ segmented downloads

    private static final int MAX_SEGMENTS = 8;
    private static final long MIN_SEGMENTED_BYTES = 1024L * 1024L; // 1 MiB

    /** Bytes already saved for {@code out}: the published partial plus any segment files. */
    public static long partialBytes(File out) {
        if (out == null) {
            return 0;
        }
        long bytes = out.exists() ? out.length() : 0;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            File seg = segmentFile(out, i);
            if (seg.exists()) {
                bytes += seg.length();
            }
        }
        return bytes;
    }

    /** Remove the partial file plus every segment and plan file owned by it. */
    public static void deletePartial(File out) {
        if (out == null) {
            return;
        }
        if (out.exists()) {
            out.delete();
        }
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            File seg = segmentFile(out, i);
            if (seg.exists()) {
                seg.delete();
            }
        }
        File meta = metaFile(out);
        if (meta.exists()) {
            meta.delete();
        }
    }

    private static File segmentFile(File out, int index) {
        return new File(out.getAbsolutePath() + ".seg" + index);
    }

    private static File metaFile(File out) {
        return new File(out.getAbsolutePath() + ".segmeta");
    }

    private static String urlKey(String url) {
        return url == null ? "" : Integer.toHexString(url.hashCode()) + ":" + url.length();
    }

    private static final class SegPlan {
        final long total;
        final int count;
        final String key;

        SegPlan(long total, int count, String key) {
            this.total = total;
            this.count = count;
            this.key = key;
        }

        long start(int index) {
            return total * index / count;
        }

        long length(int index) {
            return total * (index + 1) / count - start(index);
        }
    }

    /** Internal signal that a server/file cannot be fetched over ranges. */
    private static final class SegUnsupported extends IOException {
        SegUnsupported() {
            super("segmented download unsupported");
        }
    }

    private static SegPlan loadPlan(File out) {
        File meta = metaFile(out);
        if (!meta.exists()) {
            return null;
        }
        try {
            String text = readSmall(meta);
            String[] f = text.trim().split("\\s+");
            if (f.length < 4 || !"v1".equals(f[0])) {
                deletePartial(out);
                return null;
            }
            long total = Long.parseLong(f[1]);
            int count = Integer.parseInt(f[2]);
            String key = f[3];
            if (total <= 0 || count < 2 || count > MAX_SEGMENTS) {
                deletePartial(out);
                return null;
            }
            // Drop segment files left behind by a wider previous plan.
            for (int i = count; i < MAX_SEGMENTS; i++) {
                File stale = segmentFile(out, i);
                if (stale.exists()) {
                    stale.delete();
                }
            }
            return new SegPlan(total, count, key);
        } catch (Exception e) {
            deletePartial(out);
            return null;
        }
    }

    private static void writePlan(File out, SegPlan plan) throws IOException {
        File meta = metaFile(out);
        File tmp = new File(meta.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(("v1 " + plan.total + " " + plan.count + " " + plan.key + "\n").getBytes("UTF-8"));
            fos.flush();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(meta)) {
            meta.delete();
            if (!tmp.renameTo(meta)) {
                tmp.delete();
                throw new IOException("segmeta_write_failed");
            }
        }
    }

    private static String readSmall(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while (bos.size() < 512 && (n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** Probe one range request; null means "use the single-stream path". */
    private static SegPlan probePlan(String url, String referer, String ua,
                                     CancelToken token, int connections) throws IOException {
        String cur = url;
        HttpURLConnection c = null;
        int code = -1;
        for (int hop = 0; hop <= 5; hop++) {
            checkRequestScope();
            if (token != null && token.stopped()) {
                throw new Cancelled();
            }
            c = open(cur, referer, ua);
            c.setConnectTimeout(boundedTimeout(20000));
            c.setReadTimeout(boundedTimeout(60000));
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=0-");
            code = c.getResponseCode();
            if (code < 300 || code >= 400) {
                break;
            }
            String loc = c.getHeaderField("Location");
            String next = loc == null ? null : new URL(new URL(cur), loc).toString();
            c.disconnect();
            c = null;
            if (next == null) {
                throw new HttpException(code);
            }
            requireHttps(next);
            cur = next;
        }
        if (c == null) {
            throw new IOException("too many redirects");
        }
        try {
            if (code == 206) {
                long total = rangeTotal(c.getHeaderField("Content-Range"));
                long start = rangeStart(c.getHeaderField("Content-Range"));
                int count = Math.min(Math.max(2, connections), MAX_SEGMENTS);
                if (start == 0 && total >= MIN_SEGMENTED_BYTES
                        && total / count >= MIN_SEGMENTED_BYTES / 4) {
                    return new SegPlan(total, count, urlKey(url));
                }
                return null;
            }
            if (code >= 400) {
                throw new HttpException(code);
            }
            return null;                       // 200/204: server ignores ranges
        } finally {
            c.disconnect();
        }
    }

    /**
     * Download a fresh file over several range connections. Returns false when the server or
     * the file is unsuitable, in which case the caller falls back to the single stream.
     */
    private static boolean downloadSegmented(String url, File out, String referer, String ua,
                                             Progress p, CancelToken token, int connections)
            throws IOException {
        SegPlan plan = loadPlan(out);
        if (plan == null || !plan.key.equals(urlKey(url)) || plan.total < MIN_SEGMENTED_BYTES) {
            if (plan != null) {
                deletePartial(out);
            }
            plan = probePlan(url, referer, ua, token, connections);
            if (plan == null) {
                return false;
            }
            deletePartial(out);
            writePlan(out, plan);
        }
        if (out.exists() && out.length() > 0 && !out.delete()) {
            throw new IOException("output_reset_failed");
        }
        final SegPlan fp = plan;
        final CancelToken group = new CancelToken();
        group.parent = token;
        final AtomicReference<IOException> failure = new AtomicReference<IOException>();
        final SegProgress reporter = new SegProgress(out, fp.total, p);
        reporter.report(true);
        ExecutorService workers = Executors.newFixedThreadPool(fp.count,
                new java.util.concurrent.ThreadFactory() {
                    @Override public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "snapsave-segment");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < fp.count; i++) {
                final int index = i;
                futures.add(workers.submit(new Runnable() {
                    @Override public void run() {
                        try {
                            if (group.stopped()) {
                                throw new Cancelled();
                            }
                            fetchSegment(url, segmentFile(out, index), referer, ua,
                                    fp.start(index), fp.length(index), fp.total, group, reporter);
                        } catch (Throwable error) {
                            if (error instanceof Cancelled
                                    && (token == null || !token.stopped())) {
                                return;         // sibling abort, not the primary failure
                            }
                            if (error instanceof IOException) {
                                failure.compareAndSet(null, (IOException) error);
                            } else {
                                failure.compareAndSet(null, new IOException(error));
                            }
                            group.cancelled = true;
                        }
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    group.cancelled = true;
                    throw new Cancelled();
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (cause instanceof IOException) {
                        failure.compareAndSet(null, (IOException) cause);
                    } else {
                        failure.compareAndSet(null, new IOException(cause));
                    }
                    group.cancelled = true;
                }
            }
        } finally {
            workers.shutdownNow();
            try {
                workers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (token != null && token.stopped()) {
            throw new Cancelled();
        }
        IOException error = failure.get();
        if (error instanceof SegUnsupported) {
            deletePartial(out);
            return false;
        }
        if (error != null) {
            throw error;
        }
        for (int i = 0; i < fp.count; i++) {
            if (segmentFile(out, i).length() != fp.length(i)) {
                throw new IOException("segment_incomplete");
            }
        }
        concatenate(out, fp.count);
        reporter.finish();
        for (int i = 0; i < fp.count; i++) {
            segmentFile(out, i).delete();
        }
        metaFile(out).delete();
        return true;
    }

    private static void fetchSegment(String url, File seg, String referer, String ua,
                                     long offset, long length, long total,
                                     CancelToken token, SegProgress reporter) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkRequestScope();
            if (token != null && token.stopped()) {
                throw new Cancelled();
            }
            long have = seg.exists() ? seg.length() : 0;
            if (have > length) {
                truncate(seg, 0);
                have = 0;
            }
            if (have == length) {
                return;
            }
            try {
                fetchSegmentOnce(url, seg, referer, ua, offset + have, length - have,
                        total, token, reporter);
                reporter.report(true);
                return;
            } catch (Cancelled c) {
                throw c;
            } catch (SegUnsupported unsupported) {
                throw unsupported;
            } catch (HttpException http) {
                throw http;
            } catch (IOException e) {
                last = e;
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt, token);
            }
        }
        throw last == null ? new IOException("segment failed") : last;
    }

    private static void fetchSegmentOnce(String url, File seg, String referer, String ua,
                                         long from, long want, long expectedTotal,
                                         CancelToken token, SegProgress reporter) throws IOException {
        String cur = url;
        HttpURLConnection c = null;
        int code = -1;
        for (int hop = 0; hop <= 5; hop++) {
            checkRequestScope();
            if (token != null && token.stopped()) {
                throw new Cancelled();
            }
            c = open(cur, referer, ua);
            c.setConnectTimeout(boundedTimeout(20000));
            c.setReadTimeout(boundedTimeout(60000));
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=" + from + "-");
            code = c.getResponseCode();
            if (code < 300 || code >= 400) {
                break;
            }
            String loc = c.getHeaderField("Location");
            String next = loc == null ? null : new URL(new URL(cur), loc).toString();
            c.disconnect();
            c = null;
            if (next == null) {
                throw new HttpException(code);
            }
            requireHttps(next);
            cur = next;
        }
        if (c == null) {
            throw new IOException("too many redirects");
        }
        try {
            if (code == 200 || code == 416) {
                throw new SegUnsupported();
            }
            if (code >= 400) {
                throw new HttpException(code);
            }
            if (code != 206) {
                throw new SegUnsupported();
            }
            String cr = c.getHeaderField("Content-Range");
            long start = rangeStart(cr);
            long total = rangeTotal(cr);
            if (start != from || (total > 0 && total != expectedTotal)) {
                throw new SegUnsupported();
            }
            InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
            FileOutputStream fos = new FileOutputStream(seg, true);
            long remaining = want;
            try {
                byte[] buf = new byte[65536];
                while (remaining > 0) {
                    checkRequestScope();
                    if (Thread.currentThread().isInterrupted() || (token != null && token.stopped())) {
                        throw new Cancelled();
                    }
                    c.setReadTimeout(boundedTimeout(60000));
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    checkRequestScope();
                    if (n <= 0) {
                        break;
                    }
                    if (token != null && token.stopped()) {
                        throw new Cancelled();
                    }
                    fos.write(buf, 0, n);
                    remaining -= n;
                    reporter.report(false);
                }
                fos.flush();
            } finally {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (remaining > 0) {
                throw new IOException("segment incomplete " + remaining);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void backoff(int attempt, CancelToken token) throws IOException {
        long pause = 700L * attempt;
        while (pause > 0) {
            checkRequestScope();
            if (token != null && token.stopped()) {
                throw new Cancelled();
            }
            long slice = Math.min(100L, pause);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
            pause -= slice;
        }
        checkRequestScope();
    }

    private static void concatenate(File out, int count) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(out, false);
        byte[] buf = new byte[65536];
        try {
            for (int i = 0; i < count; i++) {
                FileInputStream fis = new FileInputStream(segmentFile(out, i));
                try {
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                } finally {
                    fis.close();
                }
            }
            fos.flush();
        } finally {
            fos.close();
        }
    }

    /** Aggregates progress by summing segment files, so no byte counter can drift on retry. */
    private static final class SegProgress {
        private final File out;
        private final long total;
        private final Progress progress;
        private long last;

        SegProgress(File out, long total, Progress progress) {
            this.out = out;
            this.total = total;
            this.progress = progress;
        }

        synchronized void report(boolean force) {
            if (progress == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!force && now - last < 150) {
                return;
            }
            last = now;
            long done = 0;
            for (int i = 0; i < MAX_SEGMENTS; i++) {
                File seg = segmentFile(out, i);
                if (seg.exists()) {
                    done += seg.length();
                }
            }
            progress.onProgress(Math.min(done, total), total);
        }

        synchronized void finish() {
            if (progress != null) {
                progress.onProgress(total, total);
            }
        }
    }

    /** Hard cap on buffered response bodies — a hostile/huge page must not exhaust the heap. */
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private static String readAll(InputStream in, HttpURLConnection connection) throws IOException {
        checkRequestScope();
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (bos.size() < MAX_BODY) {
                checkRequestScope();
                connection.setReadTimeout(boundedTimeout(18000));
                n = in.read(buf, 0, Math.min(buf.length, MAX_BODY - bos.size()));
                checkRequestScope();
                if (n <= 0) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** Simple cooperative cancellation token shared with the worker thread. */
    public static final class CancelToken {
        public volatile boolean cancelled = false;
        /**
         * Optional parent token. Segmented downloads give every connection its own token and
         * point it at the user-facing task token, so a pause stops every connection at once.
         */
        public volatile CancelToken parent;

        /** True when this token or any parent token was cancelled. */
        public boolean stopped() {
            CancelToken t = this;
            for (int guard = 0; t != null && guard < 8; guard++) {
                if (t.cancelled) {
                    return true;
                }
                t = t.parent;
            }
            return false;
        }
    }
}
