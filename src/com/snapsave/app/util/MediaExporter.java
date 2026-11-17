package com.snapsave.app.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Copies an app-private download into public MediaStore storage so Gallery, Music and file
 * managers can see it. On API 29+ no storage permission is needed; older releases require
 * WRITE_EXTERNAL_STORAGE (declared with maxSdkVersion 28).
 */
public final class MediaExporter {

    private MediaExporter() {
    }

    public static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < 29;
    }

    /** Public MIME type used for share/open intents. */
    public static String mimeType(DownloadTask task) {
        String type = MediaTypes.mediaType(task);
        return mime(task, MediaTypes.extension(task),
                MediaTypes.AUDIO.equals(type), MediaTypes.IMAGE.equals(type));
    }

    public static Uri export(Context context, DownloadTask task) throws Exception {
        return export(context, task, null);
    }

    /**
     * @param displayName optional new file name (with or without extension); null keeps the
     *                    published name.
     */
    public static Uri export(Context context, DownloadTask task, String displayName) throws Exception {
        if (task == null || task.outFile == null || !task.outFile.exists()) {
            throw new Exception("This file is no longer available.");
        }
        File source = task.outFile;
        String type = MediaTypes.mediaType(task);
        boolean audio = MediaTypes.AUDIO.equals(type);
        boolean image = MediaTypes.IMAGE.equals(type);
        String extension = MediaTypes.extension(task);
        String mime = mime(task, extension, audio, image);
        String name = displayName == null || displayName.trim().isEmpty()
                ? source.getName() : withExtension(sanitize(displayName), extension);
        Uri uri = exportFile(context, source, name, mime, audio, image);
        task.exportedUri = uri == null ? null : uri.toString();
        return uri;
    }

    private static Uri exportFile(Context context, File source, String name, String mime,
                                  boolean audio, boolean image) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            return Api29.export(resolver, source, name, mime, audio, image);
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(relativePath(audio, image)),
                "SnapSave");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Could not create the public SnapSave folder.");
        }
        File target = unique(new File(dir, name));
        copy(source, new FileOutputStream(target));
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, target.getAbsolutePath());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, target.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.SIZE, target.length());
        Uri uri = resolver.insert(collection(audio, image), values);
        return uri != null ? uri : Uri.fromFile(target);
    }

    /**
     * API 29+ MediaStore path. Isolated in its own class so RELATIVE_PATH and IS_PENDING are
     * never resolved on Android 9 or older, where those fields do not exist.
     */
    private static final class Api29 {
        static Uri export(ContentResolver resolver, File source, String name, String mime,
                          boolean audio, boolean image) throws Exception {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    relativePath(audio, image) + "/SnapSave");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = collection(audio, image);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new Exception("Could not create a public media entry.");
            }
            try {
                copy(source, resolver.openOutputStream(uri));
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return uri;
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                throw e;
            }
        }
    }

    private static Uri collection(boolean audio, boolean image) {
        if (audio) {
            return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }
        if (image) {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    private static String relativePath(boolean audio, boolean image) {
        if (audio) {
            return Environment.DIRECTORY_MUSIC;
        }
        if (image) {
            return Environment.DIRECTORY_PICTURES;
        }
        return Environment.DIRECTORY_MOVIES;
    }

    private static String mime(DownloadTask task, String extension, boolean audio, boolean image) {
        String mime = task.mimeType;
        if (mime != null && mime.contains("/") && !mime.endsWith("/*")) {
            return mime;
        }
        if (".mp4".equals(extension) || ".m4v".equals(extension)) {
            return "video/mp4";
        }
        if (".mkv".equals(extension)) {
            return "video/x-matroska";
        }
        if (".webm".equals(extension)) {
            return "video/webm";
        }
        if (".mov".equals(extension)) {
            return "video/quicktime";
        }
        if (".m4a".equals(extension)) {
            return "audio/mp4";
        }
        if (".mp3".equals(extension)) {
            return "audio/mpeg";
        }
        if (".aac".equals(extension)) {
            return "audio/aac";
        }
        if (".opus".equals(extension)) {
            return "audio/opus";
        }
        if (".flac".equals(extension)) {
            return "audio/flac";
        }
        if (".wav".equals(extension)) {
            return "audio/wav";
        }
        if (".ogg".equals(extension) || ".oga".equals(extension)) {
            return "audio/ogg";
        }
        if (".jpg".equals(extension) || ".jpeg".equals(extension)) {
            return "image/jpeg";
        }
        if (".png".equals(extension)) {
            return "image/png";
        }
        if (".webp".equals(extension)) {
            return "image/webp";
        }
        if (".gif".equals(extension)) {
            return "image/gif";
        }
        if (audio) {
            return "audio/mpeg";
        }
        if (image) {
            return "image/jpeg";
        }
        return "video/mp4";
    }

    private static String sanitize(String name) {
        String cleaned = name == null ? "" : name.trim()
                .replace('/', '_').replace('\\', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_');
        return cleaned.isEmpty() ? "SnapSave" : cleaned;
    }

    private static String withExtension(String name, String extension) {
        if (extension == null || extension.isEmpty()) {
            return name;
        }
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(extension.toLowerCase(java.util.Locale.US)) ? name : name + extension;
    }

    private static File unique(File file) {
        if (!file.exists()) {
            return file;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(file.getParentFile(), base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(file.getParentFile(), base + "-" + System.currentTimeMillis() + ext);
    }

    private static void copy(File source, OutputStream out) throws Exception {
        if (out == null) {
            throw new Exception("Could not open the destination file.");
        }
        FileInputStream in = new FileInputStream(source);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            in.close();
            out.close();
        }
    }
}