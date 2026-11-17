package com.snapsave.app.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.snapsave.app.R;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared ownership, bounded workers and memory for both thumbnail loaders. */
final class ThumbnailRequests {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(4, 4, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(96));
    private static final Map<String, Job> JOBS = new HashMap<String, Job>();
    private static final int MEMORY_BYTES = (int) Math.max(1024 * 1024,
            Math.min(20L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8));
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MEMORY_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
        // Never recycle evictions: a visible ImageView may still own the same bitmap.
    };

    interface Loader {
        /** Called on a worker, including any filesystem stats needed for versioning. */
        String cacheKey();
        Bitmap load(Signal signal) throws Exception;
    }

    static final class Signal {
        private volatile boolean cancelled;
        private HttpURLConnection connection;

        void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new IOException("thumbnail_cancelled");
            }
        }

        synchronized void connection(HttpURLConnection next) throws IOException {
            check();
            connection = next;
        }

        synchronized void clear(HttpURLConnection current) {
            if (connection == current) connection = null;
        }

        synchronized void cancel() {
            cancelled = true;
            if (connection != null) {
                connection.disconnect();
                connection = null;
            }
        }
    }

    private ThumbnailRequests() { }

    static String key(String namespace, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((namespace + "\n" + value).getBytes("UTF-8"));
            char[] hex = new char[bytes.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                hex[i * 2] = digits[(bytes[i] & 255) >>> 4];
                hex[i * 2 + 1] = digits[bytes[i] & 15];
            }
            return new String(hex);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void load(final ImageView view, final String key, final Loader loader,
                     final int placeholderRes, final int iconRes, final String fallback) {
        if (view == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    load(view, key, loader, placeholderRes, iconRes, fallback);
                }
            });
            return;
        }
        cancel(view);
        view.setBackgroundResource(placeholderRes);
        view.setImageDrawable(iconRes == 0 ? null : new TypeIcon(
                view.getContext().getDrawable(iconRes), Util.dp(view.getContext(), 28)));
        Binding binding = new Binding(view, key, loader, fallback);
        // A keyed tag leaves callers' ordinary view tags untouched. Both loaders use this slot.
        view.setTag(R.id.thumb_bg, binding);
        view.addOnAttachStateChangeListener(binding);
        if (loader != null) submit(binding);
    }

    static void cancel(final ImageView view) {
        if (view == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override public void run() { cancel(view); }
            });
            return;
        }
        Object tag = view.getTag(R.id.thumb_bg);
        if (tag instanceof Binding) {
            Binding binding = (Binding) tag;
            binding.cancelled = true;
            unsubscribe(binding);
            view.removeOnAttachStateChangeListener(binding);
            view.setTag(R.id.thumb_bg, null);
        }
    }

    static void enqueue(Binding binding, String key, Loader loader) {
        binding.key = key;
        binding.loader = loader;
        submit(binding);
    }

    private static void submit(Binding binding) {
        if (!binding.current() || binding.suspended || binding.complete || binding.job != null) return;
        synchronized (LOCK) {
            Job job = JOBS.get(binding.key);
            if (job != null) {
                job.bindings.add(binding);
                binding.job = job;
                return;
            }
            job = new Job(binding.key, binding.loader);
            job.bindings.add(binding);
            binding.job = job;
            JOBS.put(job.key, job);
            try {
                POOL.execute(job.future);
            } catch (RejectedExecutionException full) {
                JOBS.remove(job.key);
                binding.job = null;
                // Leave the type placeholder. Never run IO on the submitting (UI) thread.
            }
        }
    }

    private static void unsubscribe(Binding binding) {
        synchronized (LOCK) {
            Job job = binding.job;
            binding.job = null;
            if (job == null) return;
            job.bindings.remove(binding);
            boolean needed = false;
            for (Binding other : job.bindings) {
                if (!other.cancelled && !other.suspended && other.view.get() != null) {
                    needed = true;
                    break;
                }
            }
            if (!needed) {
                if (JOBS.get(job.key) == job) JOBS.remove(job.key);
                job.signal.cancel();
                job.future.cancel(true);
                POOL.remove(job.future);
            }
        }
    }

    static final class Binding implements View.OnAttachStateChangeListener {
        final WeakReference<ImageView> view;
        String key;
        Loader loader;
        String fallback;
        volatile boolean cancelled;
        volatile boolean suspended;
        boolean complete;
        Job job;

        Binding(ImageView view, String key, Loader loader, String fallback) {
            this.view = new WeakReference<ImageView>(view);
            this.key = key;
            this.loader = loader;
            this.fallback = fallback;
        }

        boolean current() {
            ImageView target = view.get();
            return !cancelled && target != null && target.getTag(R.id.thumb_bg) == this;
        }

        @Override public void onViewDetachedFromWindow(View view) {
            suspended = true;
            unsubscribe(this);
        }

        @Override public void onViewAttachedToWindow(View view) {
            suspended = false;
            if (loader != null) submit(this);
        }
    }

    private static final class Job implements Runnable {
        final String key;
        final Loader loader;
        final Signal signal = new Signal();
        final ArrayList<Binding> bindings = new ArrayList<Binding>();
        final FutureTask<Void> future;

        Job(String key, Loader loader) {
            this.key = key;
            this.loader = loader;
            future = new FutureTask<Void>(this, null);
        }

        @Override public void run() {
            Bitmap result = null;
            try {
                signal.check();
                String version = loader.cacheKey();
                result = CACHE.get(version);
                if (result != null && result.isRecycled()) {
                    CACHE.remove(version);
                    result = null;
                }
                if (result == null) {
                    result = loader.load(signal);
                    signal.check();
                    if (result != null && !result.isRecycled()) CACHE.put(version, result);
                }
            } catch (Exception ignored) {
                result = null;
            } catch (OutOfMemoryError exhausted) {
                CACHE.evictAll();
                result = null;
            }
            final Bitmap bitmap = result;
            // Keep the job discoverable until delivery, so a bind between decode and delivery
            // joins this result instead of starting a second disk/network request.
            MAIN.post(new Runnable() {
                @Override public void run() {
                    ArrayList<Binding> targets;
                    synchronized (LOCK) {
                        if (JOBS.get(key) == Job.this) JOBS.remove(key);
                        targets = new ArrayList<Binding>(bindings);
                        bindings.clear();
                    }
                    for (Binding binding : targets) {
                        if (binding.job != Job.this) continue;
                        binding.job = null;
                        if (!binding.current() || binding.suspended) continue;
                        ImageView target = binding.view.get();
                        if (target == null) continue;
                        if (bitmap != null && !bitmap.isRecycled()) {
                            target.setImageBitmap(bitmap);
                            binding.complete = true;
                            binding.loader = null;
                        } else if (binding.fallback != null && !binding.fallback.isEmpty()) {
                            String remote = binding.fallback;
                            binding.fallback = null;
                            ImageLoader.enqueue(binding, remote);
                        } else {
                            binding.complete = true;
                            binding.loader = null;
                        }
                    }
                }
            });
        }
    }

    /** A real type glyph with fixed visual size, independent of the ImageView's scaleType. */
    private static final class TypeIcon extends Drawable {
        private final Drawable icon;
        private final int size;

        TypeIcon(Drawable icon, int size) {
            this.icon = icon.mutate();
            this.size = size;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            int edge = Math.min(size, Math.min(b.width(), b.height()));
            int left = b.centerX() - edge / 2;
            int top = b.centerY() - edge / 2;
            icon.setBounds(left, top, left + edge, top + edge);
            icon.draw(canvas);
        }
        @Override public void setAlpha(int alpha) { icon.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { icon.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
