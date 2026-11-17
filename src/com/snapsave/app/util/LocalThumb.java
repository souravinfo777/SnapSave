package com.snapsave.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thumbnail loader for files that already live on this device. Video frames and embedded album
 * art replace the generic gradient placeholder in My Files and in completed download rows.
 */
public final class LocalThumb {

    private static final int TARGET_WIDTH = 320;
    private static final int TARGET_HEIGHT = 180;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LocalThumb() {
    }

    public static void load(final ImageView view, final File file, final String mediaType,
                            final int placeholderRes) {
        view.setBackgroundResource(placeholderRes);
        if (file == null || !file.isFile()) {
            view.setImageDrawable(null);
            view.setTag(null);
            return;
        }
        final String key = file.getAbsolutePath() + ":" + file.lastModified() + ":" + mediaType;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setTag(key);
            return;
        }
        view.setImageDrawable(null);
        view.setTag(key);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decode(file, mediaType);
                if (bmp == null) {
                    return;
                }
                CACHE.put(key, bmp);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(view.getTag())) {
                            view.setImageBitmap(bmp);
                        }
                    }
                });
            }
        });
    }

    private static Bitmap decode(File file, String mediaType) {
        if ("image".equals(mediaType)) {
            return decodeImage(file);
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            if ("audio".equals(mediaType)) {
                byte[] art = retriever.getEmbeddedPicture();
                if (art == null) {
                    return null;
                }
                Bitmap raw = BitmapFactory.decodeByteArray(art, 0, art.length);
                return raw == null ? null : scale(raw);
            }
            if (Build.VERSION.SDK_INT >= 27) {
                return retriever.getScaledFrameAtTime(1000000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_WIDTH, TARGET_HEIGHT);
            }
            Bitmap raw = retriever.getFrameAtTime(1000000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return raw == null ? null : scale(raw);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap decodeImage(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (max / sample > TARGET_WIDTH * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap scale(Bitmap raw) {
        int width = raw.getWidth();
        int height = raw.getHeight();
        if (width <= TARGET_WIDTH && height <= TARGET_HEIGHT) {
            return raw;
        }
        float ratio = Math.min((float) TARGET_WIDTH / width, (float) TARGET_HEIGHT / height);
        Bitmap scaled = Bitmap.createScaledBitmap(raw,
                Math.max(1, (int) (width * ratio)), Math.max(1, (int) (height * ratio)), true);
        if (scaled != raw) {
            raw.recycle();
        }
        return scaled;
    }
}