package com.snapsave.app.engine;

import com.snapsave.app.model.StreamOption;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Runs actual production logic with controlled backend responses and loopback HTTPS only. */
public final class HostRegression {
    private static File work;
    private static int passed;
    private static final byte[] PARTIAL = "old partial bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MEDIA = "0123456789-host-media".getBytes(StandardCharsets.UTF_8);
    /** Larger than the 1 MiB segmented threshold so the parallel path is really exercised. */
    private static final byte[] BIG = bigPayload(2 * 1024 * 1024 + 12345);

    private static byte[] bigPayload(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 31 + (i >>> 7)) & 0xFF);
        }
        return bytes;
    }

    private interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        work = new File(args[0]);
        run("server token stays exact; missing token never falls back to quality", new Case() {
            public void run() {
                DownloadTask task = task();
                task.formatToken = "1080p";
                task.formatItag = 22;
                ResolvedMedia media = new ResolvedMedia();
                StreamOption lower = video("https://cdn.test/720", 720, true, 22);
                lower.serverFormat = "720p";
                StreamOption exact = video("https://cdn.test/1080", 1080, true, 0);
                exact.serverFormat = "1080p";
                media.videoStreams.add(lower);
                media.videoStreams.add(exact);
                check(TaskRefresh.match(task, media) == exact, "token must outrank a coincidentally matching itag");
                media.videoStreams.remove(exact);
                check(TaskRefresh.match(task, media) == null, "missing format must not silently downgrade");
            }
        });
        run("extract-audio refresh selects original video itag, not an audio row", new Case() {
            public void run() {
                DownloadTask task = task();
                task.selectedAudioOnly = true;
                task.sourceIsVideo = true;
                task.formatItag = 137;
                ResolvedMedia media = new ResolvedMedia();
                media.audioStreams.add(audio("https://cdn.test/wrong-audio", 128));
                StreamOption wrong = video("https://cdn.test/wrong-video", 1080, false, 248);
                StreamOption exact = video("https://cdn.test/right-video", 1080, false, 137);
                media.videoStreams.add(wrong);
                media.videoStreams.add(exact);
                check(TaskRefresh.match(task, media) == exact, "local extraction must refresh its original video");
                media.videoStreams.remove(exact);
                check(TaskRefresh.match(task, media) == null, "same height is insufficient when an itag was selected");
            }
        });
        run("legacy audio selection requires MIME, bitrate and progressive mode", new Case() {
            public void run() {
                DownloadTask task = task();
                task.selectedAudioOnly = true;
                task.sourceMime = "audio/mp4";
                task.formatBitrate = 128;
                ResolvedMedia media = new ResolvedMedia();
                StreamOption wrongMime = audio("https://cdn.test/opus", 128);
                wrongMime.mimeType = "audio/opus";
                StreamOption wrongMode = audio("https://cdn.test/progressive", 128);
                wrongMode.progressive = true;
                media.audioStreams.add(wrongMime);
                media.audioStreams.add(audio("https://cdn.test/low", 64));
                media.audioStreams.add(wrongMode);
                StreamOption exact = audio("https://cdn.test/exact", 128);
                media.audioStreams.add(exact);
                check(TaskRefresh.match(task, media) == exact, "legacy fields must preserve the selected audio format");
                media.audioStreams.remove(exact);
                check(TaskRefresh.match(task, media) == null, "must reject nearby audio formats");
            }
        });
        run("changed adaptive URLs delete both partials and reset byte counters", new Case() {
            public void run() throws Exception {
                DownloadTask task = task();
                task.formatItag = 137;
                DownloadTask.Part video = part(task, "https://cdn.test/old-video");
                DownloadTask.Part audio = part(task, "https://cdn.test/old-audio");
                ResolvedMedia media = new ResolvedMedia();
                media.videoStreams.add(video("https://cdn.test/new-video", 1080, false, 137));
                media.bestAudio = audio("https://cdn.test/new-audio", 128);
                Resolvers.result = media;
                check(TaskRefresh.refresh(task), "exact adaptive refresh must succeed");
                check(!video.temp.exists() && !audio.temp.exists()
                        && video.done == 0 && video.total == 0 && audio.done == 0 && audio.total == 0
                        && "https://cdn.test/new-video".equals(video.url)
                        && "https://cdn.test/new-audio".equals(audio.url)
                        && task.sourcePage.equals(video.referer)
                        && task.sourcePage.equals(Resolvers.requestedPage),
                        "new URLs must never append to old bytes");
            }
        });
        run("identical signed URL preserves existing bytes and progress", new Case() {
            public void run() throws Exception {
                DownloadTask task = task();
                task.formatItag = 22;
                DownloadTask.Part part = part(task, "https://cdn.test/same");
                ResolvedMedia media = new ResolvedMedia();
                media.videoStreams.add(video(part.url, 720, true, 22));
                Resolvers.result = media;
                check(TaskRefresh.refresh(task) && preserved(part), "same entity URL must retain a resumable partial");
            }
        });
        run("unavailable selected format leaves partial bytes and URL untouched", new Case() {
            public void run() throws Exception {
                DownloadTask task = task();
                task.formatItag = 137;
                DownloadTask.Part part = part(task, "https://cdn.test/selected");
                ResolvedMedia media = new ResolvedMedia();
                media.videoStreams.add(video("https://cdn.test/lower", 720, true, 22));
                Resolvers.result = media;
                check(!TaskRefresh.refresh(task) && preserved(part)
                        && "https://cdn.test/selected".equals(part.url), "failed refresh must not destroy a recoverable selection");
            }
        });
        run("FastSaver refresh sends persisted format token and resets stale bytes", new Case() {
            public void run() throws Exception {
                DownloadTask task = task();
                task.provider = "fastsaver";
                task.formatToken = "mp3";
                DownloadTask.Part part = part(task, "https://cdn.test/expired");
                FastSaver.result = "https://cdn.test/fresh-mp3";
                check(TaskRefresh.refresh(task) && FastSaver.calls == 1 && Resolvers.calls == 0
                        && task.sourcePage.equals(FastSaver.requestedPage)
                        && "mp3".equals(FastSaver.requestedFormat)
                        && FastSaver.result.equals(part.url) && !part.temp.exists()
                        && part.done == 0 && part.total == 0, "backend must renew the exact persisted format");
            }
        });
        run("cancellation before or during resolution preserves the partial", new Case() {
            public void run() throws Exception {
                final DownloadTask task = task();
                task.formatItag = 22;
                DownloadTask.Part part = part(task, "https://cdn.test/original");
                task.cancelToken.cancelled = true;
                check(!TaskRefresh.refresh(task) && Resolvers.calls == 0 && preserved(part), "pre-cancel must avoid backend work");
                task.cancelToken.cancelled = false;
                ResolvedMedia media = new ResolvedMedia();
                media.videoStreams.add(video("https://cdn.test/replacement", 720, true, 22));
                Resolvers.result = media;
                Resolvers.afterResolve = new Runnable() {
                    public void run() { task.cancelToken.cancelled = true; }
                };
                try {
                    TaskRefresh.refresh(task);
                    throw new AssertionError("cancellation after backend response must be surfaced");
                } catch (Http.Cancelled expected) {
                    check(preserved(part) && "https://cdn.test/original".equals(part.url), "cancel must precede any file mutation");
                }
            }
        });
        run("generic file tasks classify MP3 audio and images from actual metadata", new Case() {
            public void run() {
                DownloadTask mp3 = task();
                mp3.kind = "file";
                mp3.outName = "Saved SONG.MP3";
                DownloadTask image = task();
                image.kind = "file";
                image.outName = "photo";
                image.mimeType = "image/jpeg";
                check(MediaTypes.isAudio(mp3) && !MediaTypes.isVideo(mp3)
                        && ".mp3".equals(MediaTypes.extension(mp3))
                        && MediaTypes.isImage(image) && !MediaTypes.isAudio(image)
                        && MediaTypes.isAudio(null, null, "file", null, "https://cdn.test/song.M4A?sig=1#play")
                        && MediaTypes.isImage(null, null, "file", "PHOTO.WEBP", null),
                        "processing kind=file must not hide playable audio or promote a photo to audio/video");
            }
        });
        try (final TlsFixture fixture = new TlsFixture(new File(args[1]))) {
            run("request scope cancellation stops HTTP and clears on close", new Case() {
                public void run() throws Exception {
                    try (Http.RequestScope scope = Http.openRequestScope(10000)) {
                        check(Http.requestScope() == scope, "scope must be attached to the current request thread");
                        scope.cancel();
                        try {
                            Http.get(fixture.url("/must-not-be-requested"), null, null);
                            throw new AssertionError("cancelled scope must stop HTTP with Http.Cancelled");
                        } catch (Http.Cancelled expected) {
                            check(scope.remainingMs() == 0
                                    && ResolveFailure.codeFor(expected) == ResolveFailure.Code.CANCELLED,
                                    "cancellation must not be mislabeled as a deadline failure");
                        }
                    }
                    check(Http.requestScope() == null, "closing a request must not poison the next request");
                }
            });
            run("request scope deadline stops HTTP with typed timeout and clears on close", new Case() {
                public void run() throws Exception {
                    try (Http.RequestScope scope = Http.openRequestScope(1)) {
                        // Wait for the public budget to expire; no reflection or private clock mutation.
                        long stop = System.nanoTime() + 2000000000L;
                        while (scope.remainingMs() > 0 && System.nanoTime() < stop) Thread.sleep(2);
                        try {
                            Http.get(fixture.url("/must-not-be-requested"), null, null);
                            throw new AssertionError("expired scope must stop HTTP with Http.Timeout");
                        } catch (Http.Timeout expected) {
                            check(ResolveFailure.codeFor(expected) == ResolveFailure.Code.TIMEOUT,
                                    "deadline must remain distinguishable from cancellation/network errors");
                        }
                    }
                    check(Http.requestScope() == null, "expired scope must be cleared on close");
                }
            });
            run("HTTPS 206 resumes from disk length and reports full progress", new Case() {
                public void run() throws Exception {
                    File out = output("range", Arrays.copyOf(MEDIA, 5));
                    final long[] progress = {-1, -1};
                    Http.download(fixture.url("/range"), out, null, null, new Http.Progress() {
                        public void onProgress(long done, long total) { progress[0] = done; progress[1] = total; }
                    }, new Http.CancelToken());
                    check(Arrays.equals(MEDIA, Files.readAllBytes(out.toPath()))
                            && "bytes=5-".equals(fixture.range.get())
                            && progress[0] == MEDIA.length && progress[1] == MEDIA.length,
                            "206 must append exactly the remaining bytes");
                }
            });
            run("HTTPS 200 ignoring Range replaces partial; 416 trims oversized complete file", new Case() {
                public void run() throws Exception {
                    File ignored = output("ignored-range", PARTIAL);
                    Http.download(fixture.url("/ignore"), ignored, null, null, null, new Http.CancelToken());
                    File complete = output("complete", Arrays.copyOf(MEDIA, MEDIA.length + 4));
                    Http.download(fixture.url("/complete"), complete, null, null, null, new Http.CancelToken());
                    check(Arrays.equals(MEDIA, Files.readAllBytes(ignored.toPath()))
                            && Arrays.equals(MEDIA, Files.readAllBytes(complete.toPath())),
                            "range fallback/completion must not concatenate or leave trailing garbage");
                }
            });
            run("HTTPS download failure retains typed status without damaging the partial", new Case() {
                public void run() throws Exception {
                    File out = output("http-status", PARTIAL);
                    try {
                        Http.download(fixture.url("/missing"), out, null, null, null, new Http.CancelToken());
                        throw new AssertionError("expected Http.HttpException from the 404 fixture");
                    } catch (Http.HttpException expected) {
                        check(expected.status == 404
                                && ResolveFailure.codeFor(expected) == ResolveFailure.Code.NETWORK
                                && Arrays.equals(PARTIAL, Files.readAllBytes(out.toPath())),
                                "HTTP status must remain available to callers and preserve resumable bytes");
                    }
                }
            });
            run("cancelled HTTP download leaves partial intact without a request", new Case() {
                public void run() throws Exception {
                    File out = output("cancelled-http", PARTIAL);
                    Http.CancelToken token = new Http.CancelToken();
                    token.cancelled = true;
                    try {
                        Http.download(fixture.url("/must-not-be-requested"), out, null, null, null, token);
                        throw new AssertionError("expected typed Http.Cancelled");
                    } catch (Http.Cancelled expected) {
                        check(Arrays.equals(PARTIAL, Files.readAllBytes(out.toPath())), "HTTP cancel must preserve resumable bytes");
                    }
                }
            });
            run("segmented download uses parallel byte ranges and produces exact bytes", new Case() {
                public void run() throws Exception {
                    File out = new File(work, "segmented-" + System.nanoTime() + ".bin");
                    final long[] progress = {-1, -1};
                    Http.download(fixture.url("/multi"), out, null, null, new Http.Progress() {
                        public void onProgress(long done, long total) { progress[0] = done; progress[1] = total; }
                    }, new Http.CancelToken(), 4);
                    check(Arrays.equals(BIG, Files.readAllBytes(out.toPath())),
                            "segmented output must match the source byte for byte");
                    check(progress[0] == BIG.length && progress[1] == BIG.length,
                            "segmented progress must end at the advertised total");
                    check(fixture.rangedRequests.get() >= 3,
                            "a four-connection transfer must request several non-zero ranges");
                    check(!new File(out.getAbsolutePath() + ".segmeta").exists(),
                            "segment plan must be cleaned after concatenation");
                }
            });
            run("segmented download falls back when a server ignores Range", new Case() {
                public void run() throws Exception {
                    File out = new File(work, "fallback-" + System.nanoTime() + ".bin");
                    Http.download(fixture.url("/ignore"), out, null, null, null, new Http.CancelToken(), 4);
                    check(Arrays.equals(MEDIA, Files.readAllBytes(out.toPath())),
                            "range-less server must still produce the exact single-stream file");
                }
            });
        }
        System.out.println("PASS: " + passed + " host regression cases (real production sources; no Android runtime)");
    }

    private static void run(String name, Case test) throws Exception {
        Resolvers.reset();
        FastSaver.reset();
        try {
            test.run();
            passed++;
            System.out.println("  PASS " + name);
        } catch (Throwable failure) {
            System.err.println("  FAIL " + name);
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static DownloadTask task() {
        DownloadTask task = new DownloadTask();
        task.sourcePage = "https://example.test/watch/selected";
        return task;
    }

    private static StreamOption video(String url, int height, boolean progressive, int itag) {
        StreamOption option = new StreamOption();
        option.url = url;
        option.height = height;
        option.progressive = progressive;
        option.itag = itag;
        option.mimeType = "video/mp4";
        return option;
    }

    private static StreamOption audio(String url, int bitrate) {
        StreamOption option = new StreamOption();
        option.url = url;
        option.audioOnly = true;
        option.bitrate = bitrate;
        option.mimeType = "audio/mp4";
        return option;
    }

    private static DownloadTask.Part part(DownloadTask task, String url) throws IOException {
        DownloadTask.Part part = new DownloadTask.Part();
        part.url = url;
        part.temp = output("part", PARTIAL);
        part.done = PARTIAL.length;
        part.total = 100;
        task.parts.add(part);
        return part;
    }

    private static boolean preserved(DownloadTask.Part part) throws IOException {
        return part.done == PARTIAL.length && part.total == 100 && part.temp.exists()
                && Arrays.equals(PARTIAL, Files.readAllBytes(part.temp.toPath()));
    }

    private static File output(String prefix, byte[] bytes) throws IOException {
        File file = File.createTempFile(prefix, ".part", work);
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** Standard JDK HTTPS server; no trust-all manager, external server or test dependency. */
    private static final class TlsFixture implements AutoCloseable {
        final HttpsServer server;
        final ExecutorService threads;
        final AtomicReference<String> range = new AtomicReference<String>();
        final AtomicInteger rangedRequests = new AtomicInteger();
        final javax.net.ssl.SSLSocketFactory previousFactory;

        TlsFixture(File identity) throws Exception {
            KeyStore keys = KeyStore.getInstance("JKS");
            try (FileInputStream in = new FileInputStream(identity)) {
                keys.load(in, "host-tests-only".toCharArray());
            }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, "host-tests-only".toCharArray());
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(keys);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
            previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            threads = Executors.newCachedThreadPool(new ThreadFactory() {
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "host-https-fixture");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            server.setExecutor(threads);
            server.createContext("/range", exchange -> {
                range.set(exchange.getRequestHeaders().getFirst("Range"));
                exchange.getResponseHeaders().set("Content-Range", "bytes 5-" + (MEDIA.length - 1) + "/" + MEDIA.length);
                respond(exchange, 206, Arrays.copyOfRange(MEDIA, 5, MEDIA.length));
            });
            server.createContext("/ignore", exchange -> respond(exchange, 200, MEDIA));
            server.createContext("/missing", exchange -> respond(exchange, 404, null));
            server.createContext("/complete", exchange -> {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + MEDIA.length);
                respond(exchange, 416, null);
            });
            server.createContext("/multi", exchange -> {
                String header = exchange.getRequestHeaders().getFirst("Range");
                long start = 0;
                if (header != null && header.startsWith("bytes=")) {
                    String spec = header.substring(6).trim();
                    int dash = spec.indexOf('-');
                    if (dash > 0) {
                        try {
                            start = Long.parseLong(spec.substring(0, dash));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (start > 0) {
                    rangedRequests.incrementAndGet();
                }
                if (start >= BIG.length) {
                    respond(exchange, 416, null);
                    return;
                }
                int from = (int) start;
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + from + "-" + (BIG.length - 1) + "/" + BIG.length);
                respond(exchange, 206, Arrays.copyOfRange(BIG, from, BIG.length));
            });
            server.start();
        }

        String url(String path) { return "https://127.0.0.1:" + server.getAddress().getPort() + path; }

        private static void respond(HttpExchange exchange, int code, byte[] body) throws IOException {
            try {
                exchange.sendResponseHeaders(code, body == null ? -1 : body.length);
                if (body != null) {
                    try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
                }
            } finally {
                exchange.close();
            }
        }

        public void close() {
            server.stop(0);
            threads.shutdownNow();
            HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
        }
    }
}
