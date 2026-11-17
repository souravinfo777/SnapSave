package com.snapsave.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.ImageView;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.Http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Async real thumbnails: shared requests, bounded memory/disk and sampled worker decoding. */
public final class ImageLoader {
    private static final int TARGET_PX = 512;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final long DISK_LIMIT = 32L * 1024 * 1024;
    private static final int DISK_ENTRIES = 256;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_ATTEMPTS = 2;
    private static final long REQUEST_MS = 30000;
    private static final Object DISK_LOCK = new Object();
    private static boolean diskChecked;

    private ImageLoader() { }

    /** Accepts HTTPS image URLs, file:// paths and content:// image URIs. */
    public static void load(ImageView view, String url, int placeholderRes) {
        load(view, url, placeholderRes, R.drawable.ic_play);
    }

    /** Keep a genuine type glyph visible when the source is empty, invalid or unavailable. */
    public static void load(ImageView view, String url, int placeholderRes, int typeIconRes) {
        String source = url == null ? "" : url.trim();
        ThumbnailRequests.load(view, requestKey(source), source.isEmpty() ? null : loader(source),
                placeholderRes, typeIconRes, null);
    }

    /** Cancels this view's subscription, including a request started by LocalThumb. */
    public static void cancel(ImageView view) {
        ThumbnailRequests.cancel(view);
    }

    static void enqueue(ThumbnailRequests.Binding binding, String source) {
        ThumbnailRequests.enqueue(binding, requestKey(source), loader(source));
    }

    private static String requestKey(String source) {
        return ThumbnailRequests.key("image-v2", source);
    }

    private static ThumbnailRequests.Loader loader(final String source) {
        return new ThumbnailRequests.Loader() {
            @Override public String cacheKey() {
                Uri uri = Uri.parse(source);
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    File file = new File(uri.getPath());
                    return ThumbnailRequests.key("image-file-v2", source + "\n" + file.length()
                            + "\n" + file.lastModified());
                }
                // Content may be edited in place: query its metadata on this worker.
                if ("content".equalsIgnoreCase(uri.getScheme())) {
                    return LocalThumb.imageKey(App.ctx(), uri, source);
                }
                return requestKey(source);
            }
            @Override public Bitmap load(ThumbnailRequests.Signal signal) throws Exception {
                signal.check();
                Uri uri = Uri.parse(source);
                if ("content".equalsIgnoreCase(uri.getScheme())
                        || "file".equalsIgnoreCase(uri.getScheme())) {
                    return ThumbnailBitmaps.image(App.ctx(), uri, TARGET_PX);
                }
                return remote(source, signal);
            }
        };
    }

    private static Bitmap remote(String source, ThumbnailRequests.Signal signal) throws Exception {
        URL url = secureUrl(new URL(source));
        signal.check();
        byte[] cached = readDisk(source);
        Bitmap image = ThumbnailBitmaps.bytes(cached, TARGET_PX);
        if (image != null) return image;
        // Invalid bytes never poison subsequent loads. Refetch in this same request.
        evictDisk(source);
        long deadline = SystemClock.elapsedRealtime() + REQUEST_MS;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            signal.check();
            try {
                byte[] bytes = fetchBytes(url, signal, deadline);
                image = ThumbnailBitmaps.bytes(bytes, TARGET_PX);
                signal.check();
                if (image != null) {
                    writeDisk(source, bytes);
                    return image;
                }
                // A bad image response gets at most one new network attempt, and is never cached.
            } catch (PermanentFailure stop) {
                return null;
            } catch (IOException transientFailure) {
                signal.check();
            }
            if (attempt + 1 < MAX_ATTEMPTS && SystemClock.elapsedRealtime() + 300 < deadline) {
                Thread.sleep(250);
            } else {
                break;
            }
        }
        return null;
    }

    /** Upgrade initial HTTP URLs; all hops must remain HTTPS, with normal platform TLS checks. */
    private static URL secureUrl(URL url) throws IOException {
        if (url.getUserInfo() != null) throw new PermanentFailure();
        if ("http".equalsIgnoreCase(url.getProtocol())) {
            int port = url.getPort();
            if (port != -1 && port != 80 && port != 443) throw new PermanentFailure();
            url = new URL("https", url.getHost(), -1, url.getFile());
        }
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                || url.getUserInfo() != null) throw new PermanentFailure();
        return url;
    }

    private static byte[] fetchBytes(URL start, ThumbnailRequests.Signal signal, long deadline)
            throws IOException {
        URL url = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            signal.check();
            int remaining = remaining(deadline);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                signal.connection(connection);
                connection.setConnectTimeout(Math.min(10000, remaining));
                connection.setReadTimeout(Math.min(10000, remaining));
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", Http.UA);
                connection.setRequestProperty("Accept", "image/webp,image/*;q=0.9,*/*;q=0.1");
                connection.setRequestProperty("Accept-Encoding", "identity");
                // Recompute for EACH target host; never carry another provider's referer across hops.
                String referer = referer(url.getHost());
                if (referer != null) connection.setRequestProperty("Referer", referer);
                int status = connection.getResponseCode();
                signal.check();
                remaining(deadline);
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || hop == MAX_REDIRECTS) throw new PermanentFailure();
                    URL next = new URL(url, location);
                    // A server may not downgrade a signed/image URL to cleartext.
                    if (!"https".equalsIgnoreCase(next.getProtocol())) throw new PermanentFailure();
                    url = secureUrl(next);
                    continue;
                }
                if (status == 408 || status == 429 || status >= 500) throw new IOException("retry_image");
                if (status != HttpURLConnection.HTTP_OK) throw new PermanentFailure();
                String length = connection.getHeaderField("Content-Length");
                if (length != null) {
                    try {
                        if (Long.parseLong(length) > MAX_IMAGE_BYTES) throw new PermanentFailure();
                    } catch (NumberFormatException ignored) { }
                }
                String type = connection.getContentType();
                if (type != null && (type.toLowerCase(Locale.US).startsWith("text/")
                        || type.toLowerCase(Locale.US).contains("application/json"))) {
                    throw new PermanentFailure();
                }
                connection.setReadTimeout(Math.min(10000, remaining(deadline)));
                try (InputStream in = connection.getInputStream()) {
                    return read(in, MAX_IMAGE_BYTES, signal, deadline);
                }
            } finally {
                signal.clear(connection);
                connection.disconnect();
            }
        }
        throw new PermanentFailure();
    }

    private static int remaining(long deadline) throws IOException {
        long ms = deadline - SystemClock.elapsedRealtime();
        if (ms <= 0) throw new IOException("thumbnail_timeout");
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }

    private static String referer(String host) {
        host = host.toLowerCase(Locale.US);
        if (domain(host, "ytimg.com") || domain(host, "ggpht.com") || domain(host, "youtube.com"))
            return "https://www.youtube.com/";
        if (domain(host, "cdninstagram.com") || domain(host, "instagram.com"))
            return "https://www.instagram.com/";
        if (domain(host, "fbcdn.net") || domain(host, "fbsbx.com") || domain(host, "facebook.com"))
            return "https://www.facebook.com/";
        if (domain(host, "tiktokcdn.com") || domain(host, "tiktokcdn-us.com")
                || domain(host, "tiktokcdn-eu.com") || domain(host, "tiktokv.com")
                || domain(host, "tiktok.com") || domain(host, "byteoversea.com")
                || domain(host, "ibytedtos.com") || domain(host, "muscdn.com"))
            return "https://www.tiktok.com/";
        if (domain(host, "twimg.com") || domain(host, "twitter.com") || domain(host, "x.com"))
            return "https://x.com/";
        if (domain(host, "pinimg.com") || domain(host, "pinterest.com"))
            return "https://www.pinterest.com/";
        if (domain(host, "vimeocdn.com") || domain(host, "vimeo.com")) return "https://vimeo.com/";
        if (domain(host, "sndcdn.com") || domain(host, "soundcloud.com")) return "https://soundcloud.com/";
        return null;
    }

    private static boolean domain(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    private static File cacheDir() {
        Context context = App.ctx();
        File dir = new File(context.getCacheDir(), "thumbs");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    private static File diskFile(File dir, String source) {
        return new File(dir, requestKey(source) + ".img");
    }

    private static byte[] readDisk(String source) {
        synchronized (DISK_LOCK) {
            File dir = cacheDir();
            if (dir == null) return null;
            if (!diskChecked) {
                trim(dir);
                diskChecked = true;
            }
            File file = diskFile(dir, source);
            if (!file.isFile()) return null;
            if (file.length() <= 0 || file.length() > MAX_IMAGE_BYTES) {
                file.delete();
                return null;
            }
            try (InputStream in = new FileInputStream(file)) {
                byte[] bytes = read(in, MAX_IMAGE_BYTES, null, 0);
                file.setLastModified(System.currentTimeMillis());
                return bytes;
            } catch (IOException ignored) {
                file.delete();
                return null;
            }
        }
    }

    private static byte[] read(InputStream in, int limit, ThumbnailRequests.Signal signal,
                               long deadline) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
        byte[] buffer = new byte[16384];
        int count;
        while (true) {
            if (signal != null) {
                signal.check();
                remaining(deadline);
            }
            count = in.read(buffer);
            if (count == -1) break;
            if (count > limit - bytes.size()) throw new PermanentFailure();
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static void evictDisk(String source) {
        synchronized (DISK_LOCK) {
            File dir = cacheDir();
            if (dir != null) diskFile(dir, source).delete();
        }
    }

    private static void writeDisk(String source, byte[] bytes) {
        synchronized (DISK_LOCK) {
            File dir = cacheDir();
            if (dir == null) return;
            File target = diskFile(dir, source);
            File temp = null;
            try {
                temp = File.createTempFile("thumb-", ".tmp", dir);
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(bytes);
                    out.flush();
                }
                if (!temp.renameTo(target)) {
                    if (target.exists() && !target.delete()) return;
                    if (!temp.renameTo(target)) return;
                }
                target.setLastModified(System.currentTimeMillis());
            } catch (IOException ignored) {
                // A cache failure must not hide a valid downloaded thumbnail.
            } finally {
                if (temp != null) temp.delete();
                trim(dir);
            }
        }
    }

    /** Byte AND entry bounds also cover a feed of tiny thumbnails; remove legacy hash keys. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long bytes = 0;
        int entries = 0;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (!file.getName().matches("[0-9a-f]{64}\\.img")
                    || file.length() <= 0 || file.length() > MAX_IMAGE_BYTES) {
                // All writers are serialized here; temporary files can only be from a dead process.
                file.delete();
                continue;
            }
            bytes += file.length();
            entries++;
        }
        if (bytes <= DISK_LIMIT && entries <= DISK_ENTRIES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File file : files) {
            if (bytes <= DISK_LIMIT * 3 / 4 && entries <= DISK_ENTRIES * 3 / 4) break;
            if (file.isFile()) {
                long length = file.length();
                if (file.delete()) {
                    bytes -= length;
                    entries--;
                }
            }
        }
    }

    private static final class PermanentFailure extends IOException { }
}
