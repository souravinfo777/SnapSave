package com.snapsave.app.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.snapsave.app.util.MediaExporter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Public Download/SnapSave output; transfer parts and native processing stay private. */
public final class MediaFiles {
    private static final String RELATIVE_PATH = "Download/SnapSave/";
    private static final Object PUBLISH_LOCK = new Object();

    private MediaFiles() { }

    public interface Progress {
        void onProgress(long copied, long total) throws Exception;
        void checkpoint() throws Exception;
    }

    public static Uri publish(Context context, DownloadTask task, File source, Progress progress)
            throws Exception {
        if (source == null || !source.isFile() || source.length() == 0) {
            throw new IOException("empty_output");
        }
        synchronized (PUBLISH_LOCK) {
            if (Build.VERSION.SDK_INT >= 29) {
                return Api29.publish(context, task, source, progress);
            }
            if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new IOException("storage_permission_required");
            }
            return publishLegacy(context, task, source, progress);
        }
    }

    private static Uri publishLegacy(Context context, DownloadTask task, File source, Progress progress)
            throws Exception {
        File folder = publicFolder();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("download_folder_unavailable");
        File target = unique(new File(folder, safeName(task.outName)));
        File pending = new File(folder, "." + task.id + ".pending");
        boolean done = false;
        try {
            copy(source, new FileOutputStream(pending), progress);
            if (!pending.renameTo(target)) throw new IOException("public_file_publish_failed");
            task.outFile = target;
            task.outName = target.getName();
            task.outputSize = target.length();
            Uri indexed = indexLegacy(context, target, MediaExporter.mimeType(task));
            task.outputUri = indexed == null ? null : indexed.toString();
            if (progress != null) progress.checkpoint();
            done = true;
            return indexed == null ? Uri.fromFile(target) : indexed;
        } finally {
            pending.delete();
            if (!done) {
                target.delete();
                task.outFile = null;
                task.outputUri = null;
            }
        }
    }

    private static Uri indexLegacy(Context context, File file, String mime) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DATA, file.getAbsolutePath());
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.SIZE, file.length());
            return context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        } catch (RuntimeException ignored) {
            android.media.MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
            return null;
        }
    }

    public static Uri uri(DownloadTask task) {
        if (task == null) return null;
        if (task.outputUri != null && !task.outputUri.isEmpty()) return Uri.parse(task.outputUri);
        return task.outFile == null ? null : Uri.fromFile(task.outFile);
    }

    public static boolean exists(Context context, DownloadTask task) {
        if (task == null) return false;
        if ((task.outputUri == null || task.outputUri.isEmpty()) && task.outFile != null
                && task.outFile.isFile()) return task.outFile.length() > 0;
        Uri uri = uri(task);
        if (uri == null) return false;
        try (android.content.res.AssetFileDescriptor fd = context.getContentResolver()
                .openAssetFileDescriptor(uri, "r")) {
            return fd != null;
        } catch (Exception ignored) { return false; }
    }

    public static long size(Context context, DownloadTask task) {
        if (task == null) return 0;
        if (task.outFile != null && task.outFile.isFile()) return task.outFile.length();
        return longColumn(context, uri(task), MediaStore.MediaColumns.SIZE, task.outputSize);
    }

    public static String displayName(Context context, DownloadTask task) {
        if (task == null) return "";
        if (task.outFile != null && task.outFile.isFile()) return task.outFile.getName();
        Uri uri = uri(task);
        if (uri != null) {
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (Exception ignored) { }
        }
        return task.outName == null ? "" : task.outName;
    }

    public static boolean rename(Context context, DownloadTask task, String name) throws Exception {
        name = safeName(name);
        if (task.outFile != null && task.outFile.isFile()) {
            File old = task.outFile;
            if (old.getName().equals(name)) return true;
            File target = unique(new File(old.getParentFile(), name));
            if (!old.renameTo(target)) return false;
            if (task.outputUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DATA, target.getAbsolutePath());
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, target.getName());
                context.getContentResolver().update(Uri.parse(task.outputUri), values, null, null);
            }
            task.outFile = target;
            task.outName = target.getName();
            return true;
        }
        Uri uri = uri(task);
        if (uri == null) return false;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        if (context.getContentResolver().update(uri, values, null, null) <= 0) return false;
        task.outName = displayName(context, task);
        return true;
    }

    public static boolean delete(Context context, DownloadTask task) {
        if (task == null) return true;
        Uri uri = task.outputUri == null ? null : Uri.parse(task.outputUri);
        try {
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            if (task.outFile != null && task.outFile.exists() && !task.outFile.delete()) return false;
            task.outputUri = null;
            return true;
        } catch (RuntimeException e) { return false; }
    }

    /** Reconcile a process death between inserting, copying and clearing IS_PENDING. */
    public static boolean recoverPending(Context context, DownloadTask task) {
        if (task.pendingUri == null || Build.VERSION.SDK_INT < 29) return false;
        return Api29.recover(context, task);
    }

    public static File publicFolder() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SnapSave");
    }

    public static String safeName(String name) {
        String cleaned = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        return cleaned.isEmpty() ? "SnapSave-media" : cleaned;
    }

    private static long longColumn(Context context, Uri uri, String column, long fallback) {
        if (uri == null) return fallback;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) { }
        return fallback;
    }

    private static File unique(File file) {
        if (!file.exists()) return file;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            File candidate = new File(file.getParentFile(), base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(file.getParentFile(), base + "-" + java.util.UUID.randomUUID() + ext);
    }

    private static void copy(File source, OutputStream destination, Progress progress) throws Exception {
        if (destination == null) throw new IOException("could_not_open_public_output");
        long copied = 0;
        try (InputStream in = new FileInputStream(source); OutputStream out = destination) {
            byte[] buffer = new byte[65536];
            int n;
            if (progress != null) progress.onProgress(0, source.length());
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                copied += n;
                if (progress != null) progress.onProgress(copied, source.length());
            }
            out.flush();
        }
        if (copied != source.length()) throw new IOException("publish_size_mismatch");
    }

    private static final class Api29 {
        static Uri publish(Context context, DownloadTask task, File source, Progress progress) throws Exception {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(task.outName));
            values.put(MediaStore.MediaColumns.MIME_TYPE, MediaExporter.mimeType(task));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("could_not_create_public_media_entry");
            task.pendingUri = uri.toString();
            boolean published = false;
            try {
                if (progress != null) progress.checkpoint();
                copy(source, resolver.openOutputStream(uri, "w"), progress);
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, ready, null, null) != 1) throw new IOException("publish_update_failed");
                task.outputUri = uri.toString();
                task.outputSize = source.length();
                task.outName = displayName(context, task);
                if (progress != null) progress.checkpoint();
                published = true;
                task.pendingUri = null;
                return uri;
            } finally {
                if (!published) {
                    resolver.delete(uri, null, null);
                    task.pendingUri = null;
                    task.outputUri = null;
                }
            }
        }

        static boolean recover(Context context, DownloadTask task) {
            Uri pending = Uri.parse(task.pendingUri);
            try {
                long size = longColumn(context, pending, MediaStore.MediaColumns.SIZE, -1);
                long state = longColumn(context, pending, MediaStore.MediaColumns.IS_PENDING, 1);
                if (state == 0 && size > 0 && size == task.publishTotal) {
                    task.outputUri = pending.toString();
                    task.outputSize = size;
                    task.pendingUri = null;
                    task.outName = displayName(context, task);
                    return true;
                }
                context.getContentResolver().delete(pending, null, null);
            } catch (RuntimeException ignored) { }
            task.pendingUri = null;
            task.outputUri = null;
            return false;
        }
    }
}
