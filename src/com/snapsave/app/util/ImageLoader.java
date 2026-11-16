package com.snapsave.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.snapsave.app.engine.Http;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny async image loader (thumbnail previews) with a memory cache. No third-party deps. */
public final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
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
                final Bitmap bmp = fetch(url);
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

    private static Bitmap fetch(String url) {
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
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
