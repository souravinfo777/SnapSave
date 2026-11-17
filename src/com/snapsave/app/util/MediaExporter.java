package com.snapsave.app.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.webkit.MimeTypeMap;

import com.snapsave.app.App;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaFiles;
import com.snapsave.app.engine.MediaTypes;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Share/open existing public outputs; migrate legacy private media only once, on a worker. */
public final class MediaExporter {
    private MediaExporter() { }

    public static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < 29;
    }

    public static String mimeType(DownloadTask task) {
        if (task == null) return "application/octet-stream";
        String extension = MediaTypes.extension(task);
        // The output container is authoritative after audio conversion.
        if (".mp3".equals(extension)) return "audio/mpeg";
        if (".m4a".equals(extension)) return "audio/mp4";
        if (".opus".equals(extension)) return "audio/ogg";
        if (".mkv".equals(extension)) return "video/x-matroska";
        if (!extension.isEmpty()) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    extension.substring(1).toLowerCase(Locale.US));
            if (type != null) return type;
        }
        if (task.mimeType != null && task.mimeType.contains("/") && !task.mimeType.endsWith("/*")) {
            return task.mimeType;
        }
        String type = MediaTypes.mediaType(task);
        return MediaTypes.AUDIO.equals(type) ? "audio/*"
                : MediaTypes.VIDEO.equals(type) ? "video/*"
                : MediaTypes.IMAGE.equals(type) ? "image/*" : "application/octet-stream";
    }

    public static Uri export(Context context, DownloadTask task) throws Exception {
        return export(context, task, null);
    }

    public static Uri export(final Context context, final DownloadTask task, String displayName)
            throws Exception {
        if (task == null || task.removed) throw new IOException("This download is no longer available.");
        synchronized (task) {
            if (task.outputUri != null && MediaFiles.exists(context, task)) {
                return Uri.parse(task.outputUri);
            }
            final File legacy = task.outFile;
            if (legacy == null || !legacy.isFile() || legacy.length() == 0) {
                throw new IOException("This file is no longer available.");
            }
            String oldName = task.outName;
            if (displayName != null && !displayName.trim().isEmpty()) {
                String ext = MediaTypes.extension(task);
                task.outName = MediaFiles.safeName(displayName);
                if (!task.outName.toLowerCase(Locale.US).endsWith(ext)) task.outName += ext;
            }
            task.publishTotal = legacy.length();
            try {
                Uri uri = MediaFiles.publish(context, task, legacy, new MediaFiles.Progress() {
                    @Override public void onProgress(long copied, long total) throws Exception {
                        if (task.removed) throw new IOException("download_removed");
                    }
                    @Override public void checkpoint() throws Exception {
                        if (task.removed) throw new IOException("download_removed");
                        App.get().downloads().persist();
                    }
                });
                // Preserve the old app-private file for backward compatibility until explicitly deleted.
                if (Build.VERSION.SDK_INT >= 29) task.outFile = legacy;
                App.get().downloads().persist();
                return uri;
            } catch (Exception error) {
                task.outName = oldName;
                throw error;
            }
        }
    }
}
