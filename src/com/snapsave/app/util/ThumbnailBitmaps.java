package com.snapsave.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Worker-only bounded image decoders. Never allocate the full image before downsampling. */
final class ThumbnailBitmaps {
    private static final long MAX_SOURCE_BYTES = 32L * 1024 * 1024;

    private ThumbnailBitmaps() { }

    static Bitmap bytes(byte[] bytes, int target) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = options(bounds, target);
            return options == null ? null : fit(BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.length, options), target, target);
        } catch (RuntimeException ignored) {
            return null;
        } catch (OutOfMemoryError exhausted) {
            return null;
        }
    }

    static Bitmap image(Context context, Uri uri, int target) {
        if (uri == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = open(context, uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options options = options(bounds, target);
            if (options == null) return null;
            try (InputStream in = open(context, uri)) {
                return fit(BitmapFactory.decodeStream(in, null, options), target, target);
            }
        } catch (Exception ignored) {
            return null;
        } catch (OutOfMemoryError exhausted) {
            return null;
        }
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        InputStream stream;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new IOException("missing_image_path");
            File file = new File(path);
            if (file.length() > MAX_SOURCE_BYTES) throw new IOException("image_too_large");
            stream = new FileInputStream(file);
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            stream = context.getContentResolver().openInputStream(uri);
        } else {
            throw new IOException("unsupported_image_uri");
        }
        if (stream == null) throw new IOException("missing_image_stream");
        return new LimitedInput(stream);
    }

    private static BitmapFactory.Options options(BitmapFactory.Options bounds, int target) {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int edge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while ((long) edge > (long) target * sample && sample < (1 << 29)) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inScaled = false;
        return options;
    }

    static Bitmap fit(Bitmap raw, int width, int height) {
        if (raw == null || raw.isRecycled()) return null;
        if (raw.getWidth() <= width && raw.getHeight() <= height) return raw;
        float ratio = Math.min((float) width / raw.getWidth(), (float) height / raw.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(raw, Math.max(1, (int) (raw.getWidth() * ratio)),
                Math.max(1, (int) (raw.getHeight() * ratio)), true);
        // raw has not been published or cached; it is the only bitmap safe to recycle here.
        if (scaled != raw) raw.recycle();
        return scaled;
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining = MAX_SOURCE_BYTES;

        LimitedInput(InputStream in) { super(in); }

        @Override public int read() throws IOException {
            if (remaining <= 0) throw new IOException("image_too_large");
            int value = in.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) throw new IOException("image_too_large");
            int count = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = in.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }
        @Override public boolean markSupported() { return false; }
    }
}
