package com.snapsave.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.snapsave.app.App;
import com.snapsave.app.engine.Http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny async image loader with memory + disk caching and downsampling. No third-party deps.
 * Thumbnails are decoded near {@link #TARGET_PX} instead of full size so a long feed does not
 * allocate multi-megabyte bitmaps and stutter while scrolling.
 */
public final class ImageLoader {

    private static final int TARGET_PX = 512;
    private static final int MAX_DISK_BYTES = 8 * 1024 * 1024;
    private static final long DISK_LIMIT = 32L * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    public static void load(final ImageView view, final String url, final int placeholderRes) {
        view.setBackgroundResource(placeholderRes);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            view.setTag(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setTag(url);
            return;
        }
        view.setImageDrawable(null);
        view.setTag(url);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                byte[] bytes = readDisk(url);
                if (bytes == null) {
                    bytes = fetchBytes(url);
                    if (bytes != null) {
                        writeDisk(url, bytes);
                    }
                }
                final Bitmap bmp = bytes == null ? null : decode(bytes, TARGET_PX);
                if (bmp == null) {
                    return;
                }
                CACHE.put(url, bmp);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(view.getTag())) {
                            view.setImageBitmap(bmp);
                        }
                    }
                });
            }
        });
    }

    private static String key(String url) {
        return Integer.toHexString(url.hashCode()) + "-" + url.length();
    }

    private static File cacheDir() {
        File dir = new File(App.ctx().getCacheDir(), "thumbs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File diskFile(String url) {
        return new File(cacheDir(), key(url) + ".img");
    }

    private static byte[] readDisk(String url) {
        File file = diskFile(url);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_DISK_BYTES) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream((int) file.length());
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                file.setLastModified(System.currentTimeMillis());
                return bos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDisk(String url, byte[] bytes) {
        try {
            File target = diskFile(url);
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    return;
                }
            }
            trim();
        } catch (Exception ignored) {
        }
    }

    /** Bounded disk cache: delete the oldest entries when the thumbnail directory grows. */
    private static void trim() {
        File[] files = cacheDir().listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            }
        }
        if (total <= DISK_LIMIT) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long target = DISK_LIMIT * 3 / 4;
        for (File f : files) {
            if (total <= target) {
                break;
            }
            if (f.isFile()) {
                total -= f.length();
                f.delete();
            }
        }
    }

    private static byte[] fetchBytes(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", Http.UA);
            c.setRequestProperty("Referer", "https://www.youtube.com/");
            if (c.getResponseCode() >= 400) {
                return null;
            }
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_DISK_BYTES) {
                        return null;
                    }
                }
                return bos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static Bitmap decode(byte[] bytes, int target) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (max / sample > target * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Throwable t) {
            return null;
        }
    }
}