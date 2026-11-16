package com.snapsave.app.engine;

import java.util.Locale;

/**
 * Media classification shared by the library, download queue, and players.
 *
 * Processing kind (for example "mp3" or "file") describes how a task is produced. It is not
 * sufficient to identify the file that the user receives, so MIME, the declared media type, and
 * the output/source extension are considered first. This also keeps old persisted tasks usable.
 */
public final class MediaTypes {

    public static final String AUDIO = "audio";
    public static final String VIDEO = "video";
    public static final String IMAGE = "image";
    public static final String UNKNOWN = "unknown";

    private MediaTypes() {
    }

    /** Classify a task using its public media fields and the legacy fields still on disk. */
    public static String mediaType(DownloadTask task) {
        if (task == null) {
            return UNKNOWN;
        }
        String output = task.outFile == null ? task.outName : task.outFile.getName();
        String mime = task.mimeType;
        if ((mime == null || mime.trim().isEmpty()) && task.option != null) {
            mime = task.option.mimeType;
        }
        String type = mediaType(mime, task.mediaType, null, output, null);
        if (!UNKNOWN.equals(type)) {
            return type;
        }
        if (task.option != null) {
            type = mediaType(task.option.mimeType, null, task.kind, task.outName, task.option.url);
            if (!UNKNOWN.equals(type)) {
                return type;
            }
        }
        for (DownloadTask.Part part : task.parts) {
            type = mediaType(null, null, task.kind, task.outName, part.url);
            if (!UNKNOWN.equals(type)) {
                return type;
            }
        }
        return mediaType(null, task.mediaType, task.kind, task.outName, null);
    }

    /** Classify independent fields, useful for player intents and stream rows. */
    public static String mediaType(String mimeType, String declaredType, String kind,
                                   String fileName, String sourceUrl) {
        String declared = normalize(declaredType);
        if (AUDIO.equals(declared) || VIDEO.equals(declared) || IMAGE.equals(declared)) {
            return declared;
        }

        String mime = normalize(mimeType);
        if (mime.startsWith("audio/")) {
            return AUDIO;
        }
        if (mime.startsWith("image/")) {
            return IMAGE;
        }
        if (mime.startsWith("video/")) {
            return VIDEO;
        }

        String byExtension = extensionType(fileName);
        if (!UNKNOWN.equals(byExtension)) {
            return byExtension;
        }
        byExtension = extensionType(sourceUrl);
        if (!UNKNOWN.equals(byExtension)) {
            return byExtension;
        }

        String k = normalize(kind);
        if (AUDIO.equals(k) || "mp3".equals(k) || "m4a".equals(k)) {
            return AUDIO;
        }
        if (IMAGE.equals(k)) {
            return IMAGE;
        }
        if (VIDEO.equals(k)) {
            return VIDEO;
        }
        return UNKNOWN;
    }

    public static boolean isAudio(DownloadTask task) {
        return AUDIO.equals(mediaType(task));
    }

    public static boolean isVideo(DownloadTask task) {
        return VIDEO.equals(mediaType(task));
    }

    public static boolean isImage(DownloadTask task) {
        return IMAGE.equals(mediaType(task));
    }

    public static boolean isAudio(String mimeType, String declaredType, String kind,
                                  String fileName, String sourceUrl) {
        return AUDIO.equals(mediaType(mimeType, declaredType, kind, fileName, sourceUrl));
    }

    public static boolean isImage(String mimeType, String declaredType, String kind,
                                  String fileName, String sourceUrl) {
        return IMAGE.equals(mediaType(mimeType, declaredType, kind, fileName, sourceUrl));
    }

    public static boolean isVideo(String mimeType, String declaredType, String kind,
                                  String fileName, String sourceUrl) {
        return VIDEO.equals(mediaType(mimeType, declaredType, kind, fileName, sourceUrl));
    }

    /** Best output extension for display/opening; returns an empty string when it is unknown. */
    public static String extension(DownloadTask task) {
        if (task == null) {
            return "";
        }
        String fromName = extension(task.outName);
        if (!fromName.isEmpty()) {
            return fromName;
        }
        String mime = normalize(task.mimeType);
        String fromMime = extensionForMime(mime);
        if (!fromMime.isEmpty()) {
            return fromMime;
        }
        if (task.option != null) {
            String fromOption = extension(task.option.url);
            if (!fromOption.isEmpty()) {
                return fromOption;
            }
            fromMime = extensionForMime(normalize(task.option.mimeType));
            if (!fromMime.isEmpty()) {
                return fromMime;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.US);
    }

    private static String extensionType(String value) {
        String ext = extension(value);
        if (ext.isEmpty()) {
            return UNKNOWN;
        }
        if (".mp3".equals(ext) || ".m4a".equals(ext) || ".aac".equals(ext)
                || ".opus".equals(ext) || ".flac".equals(ext) || ".wav".equals(ext)
                || ".ogg".equals(ext) || ".oga".equals(ext)) {
            return AUDIO;
        }
        if (".png".equals(ext) || ".jpg".equals(ext) || ".jpeg".equals(ext)
                || ".webp".equals(ext) || ".gif".equals(ext) || ".bmp".equals(ext)) {
            return IMAGE;
        }
        if (".mp4".equals(ext) || ".webm".equals(ext) || ".mkv".equals(ext)
                || ".mov".equals(ext) || ".3gp".equals(ext) || ".avi".equals(ext)) {
            return VIDEO;
        }
        return UNKNOWN;
    }

    private static String extensionForMime(String mime) {
        if (mime.contains("mpeg")) return ".mp3";
        if (mime.contains("mp4") && mime.startsWith("audio/")) return ".m4a";
        if (mime.contains("aac")) return ".aac";
        if (mime.contains("opus")) return ".opus";
        if (mime.contains("flac")) return ".flac";
        if (mime.contains("wav")) return ".wav";
        if (mime.contains("ogg")) return ".ogg";
        if (mime.contains("png")) return ".png";
        if (mime.contains("jpeg") || mime.contains("jpg")) return ".jpg";
        if (mime.contains("webp")) return ".webp";
        if (mime.contains("gif")) return ".gif";
        if (mime.contains("webm")) return ".webm";
        if (mime.contains("matroska")) return ".mkv";
        if (mime.contains("quicktime")) return ".mov";
        if (mime.contains("mp4")) return ".mp4";
        return "";
    }

    private static String extension(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String s = value.trim().toLowerCase(Locale.US);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int query = s.indexOf('?');
        if (query >= 0) s = s.substring(0, query);
        int slash = s.lastIndexOf('/');
        int dot = s.lastIndexOf('.');
        if (dot <= slash || dot == s.length() - 1) {
            return "";
        }
        String ext = s.substring(dot);
        return ext.matches("\\.[a-z0-9]{1,8}") ? ext : "";
    }
}
