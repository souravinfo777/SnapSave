package com.snapsave.app.util;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.ImageView;

import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaFiles;
import com.snapsave.app.engine.MediaTypes;

import java.io.File;

/** Real image/album-art/video thumbnails for public URI and legacy file outputs. */
public final class LocalThumb {
    private static final int TARGET_WIDTH = 320;
    private static final int TARGET_HEIGHT = 180;
    // Older retrievers cannot sample frames. Refuse a huge full-frame allocation on API 21-26.
    private static final long MAX_LEGACY_FRAME_PIXELS = 4L * 1024 * 1024;

    private LocalThumb() { }

    /** Public output first, then legacy File, then the saved real remote thumbnail. */
    public static void load(ImageView view, DownloadTask task, int placeholderRes) {
        if (view == null) return;
        String type = MediaTypes.mediaType(task);
        Uri uri = MediaFiles.uri(task);
        File file = task == null ? null : task.outFile;
        String remote = task == null ? null : task.thumbUrl;
        String revision = task == null ? "" : task.outputSize + "\n" + task.completedAt;
        load(view, uri, file, type, revision, remote, placeholderRes);
    }

    /** Retained for callers with legacy private/public filesystem outputs. */
    public static void load(ImageView view, File file, String mediaType, int placeholderRes) {
        load(view, file == null ? null : Uri.fromFile(file), file,
                MediaTypes.mediaType(null, mediaType, mediaType,
                        file == null ? null : file.getName(), null), "", null, placeholderRes);
    }

    /** Direct content:// or file:// media, including content-backed image files. */
    public static void loadUri(ImageView view, Uri uri, String mediaType, int placeholderRes) {
        load(view, uri, null, MediaTypes.mediaType(null, mediaType, mediaType,
                uri == null ? null : uri.getLastPathSegment(), null), "", null, placeholderRes);
    }

    /** Cancels either loader's subscription for the view, without clearing its displayed image. */
    public static void cancel(ImageView view) {
        ThumbnailRequests.cancel(view);
    }

    private static void load(ImageView view, final Uri uri, final File file, final String mediaType,
                             final String revision, String remote, int placeholderRes) {
        if (view == null) return;
        final Context context = view.getContext().getApplicationContext();
        final String identity = (uri == null ? "" : uri.toString()) + "\n"
                + (file == null ? "" : file.getAbsolutePath()) + "\n" + mediaType + "\n" + revision;
        final String fallback = remote == null || remote.trim().isEmpty() ? null : remote.trim();
        // Fallback belongs in the request identity: two tasks sharing a file may have different art.
        final String requestKey = ThumbnailRequests.key("local-request-v2", identity + "\n" + fallback);
        ThumbnailRequests.Loader loader = new ThumbnailRequests.Loader() {
            @Override public String cacheKey() {
                String version = imageKey(context, uri, identity);
                if (file != null) version += "\n" + file.length() + "\n" + file.lastModified();
                return ThumbnailRequests.key("local-bitmap-v2", version);
            }
            @Override public Bitmap load(ThumbnailRequests.Signal signal) throws Exception {
                signal.check();
                Bitmap bitmap = decode(context, uri, mediaType, signal);
                signal.check();
                if (bitmap == null && file != null) {
                    Uri legacy = Uri.fromFile(file);
                    if (!legacy.equals(uri)) bitmap = decode(context, legacy, mediaType, signal);
                }
                signal.check();
                return bitmap;
            }
        };
        ThumbnailRequests.load(view, requestKey, loader, placeholderRes, icon(mediaType), fallback);
    }

    private static int icon(String mediaType) {
        if (MediaTypes.AUDIO.equals(mediaType)) return R.drawable.ic_music;
        if (MediaTypes.VIDEO.equals(mediaType)) return R.drawable.ic_play;
        if (MediaTypes.IMAGE.equals(mediaType)) return android.R.drawable.ic_menu_gallery;
        return R.drawable.ic_folder;
    }

    /** Cache version queries/stat calls are worker-only, also used by ImageLoader's content path. */
    static String imageKey(Context context, Uri uri, String identity) {
        String version = "";
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            version = file.length() + "\n" + file.lastModified();
        } else if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
            String[] columns = {OpenableColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
            try {
                version = metadata(context, uri, columns);
            } catch (Exception unsupportedColumn) {
                try {
                    version = metadata(context, uri, new String[]{OpenableColumns.SIZE});
                } catch (Exception ignored) { }
            }
            // Some document providers have no modification column. Bound stale memory in that case.
            version += "\n" + (SystemClock.elapsedRealtime() / 60000L);
        }
        return ThumbnailRequests.key("local-source-v2", identity + "\n" + version);
    }

    private static String metadata(Context context, Uri uri, String[] columns) {
        StringBuilder result = new StringBuilder();
        try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                for (String column : columns) {
                    int index = cursor.getColumnIndex(column);
                    result.append('\n');
                    if (index >= 0 && !cursor.isNull(index)) result.append(cursor.getString(index));
                }
            }
        }
        return result.toString();
    }

    private static Bitmap decode(Context context, Uri uri, String mediaType,
                                 ThumbnailRequests.Signal signal) throws Exception {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) return null;
        signal.check();
        String type = mediaType;
        // Unknown content outputs may still provide an authoritative MIME type.
        if (MediaTypes.UNKNOWN.equals(type) && "content".equalsIgnoreCase(scheme)) {
            try {
                type = MediaTypes.mediaType(context.getContentResolver().getType(uri), null,
                        null, uri.getLastPathSegment(), null);
            } catch (Exception ignored) { }
        }
        if (MediaTypes.IMAGE.equals(type)) return ThumbnailBitmaps.image(context, uri, TARGET_WIDTH);
        if (!MediaTypes.AUDIO.equals(type) && !MediaTypes.VIDEO.equals(type)) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if ("content".equalsIgnoreCase(scheme)) {
                // Uses ContentResolver descriptors internally; no _data lookup or private copy.
                retriever.setDataSource(context, uri);
            } else {
                String path = uri.getPath();
                if (path == null) return null;
                retriever.setDataSource(path);
            }
            signal.check();
            if (MediaTypes.AUDIO.equals(type)) {
                // Bounds pass BEFORE bitmap allocation, including compressed embedded album art.
                return ThumbnailBitmaps.bytes(retriever.getEmbeddedPicture(), TARGET_WIDTH);
            }
            Bitmap bitmap = frame(retriever, 1000000L);
            signal.check();
            if (bitmap == null) bitmap = frame(retriever, -1L);
            return bitmap;
        } catch (Exception unavailable) {
            return null;
        } catch (OutOfMemoryError exhausted) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) { }
        }
    }

    private static Bitmap frame(MediaMetadataRetriever retriever, long time) {
        if (Build.VERSION.SDK_INT >= 27) {
            return ThumbnailBitmaps.fit(retriever.getScaledFrameAtTime(time,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_WIDTH, TARGET_HEIGHT),
                    TARGET_WIDTH, TARGET_HEIGHT);
        }
        try {
            long width = Long.parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            long height = Long.parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (width <= 0 || height <= 0 || width > MAX_LEGACY_FRAME_PIXELS / height) return null;
        } catch (Exception unknownSize) {
            return null;
        }
        return ThumbnailBitmaps.fit(retriever.getFrameAtTime(time,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC), TARGET_WIDTH, TARGET_HEIGHT);
    }
}
