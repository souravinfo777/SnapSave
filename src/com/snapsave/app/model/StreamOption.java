package com.snapsave.app.model;

/** One selectable download variant (a quality row in the download sheet). */
public class StreamOption {
    public String label;     // "1080p Full HD"
    public String sub;       // "MP4 • Best quality"
    public long sizeBytes;   // estimated or reported
    public int height;       // 0 for audio
    public boolean audioOnly;
    public String mimeType;  // "video/mp4" / "audio/mp4" / "audio/mpeg"
    public String url;       // direct media url (may be null until resolved)
    public int bitrate;      // kbps for audio
    public int itag;
    public boolean progressive; // true when this single file already contains audio+video
    /** Backend format token (e.g. "1080p"/"mp3") when the direct URL is obtained lazily. */
    public String serverFormat;
    /** This "audio" row is really a video file whose sound is extracted locally. */
    public boolean sourceIsVideo;
    /** Source metadata for locally converted options. */
    public String sourceMimeType;
    public int sourceBitrate;

    public StreamOption() {
    }

    public StreamOption(String label, String sub, long sizeBytes, int height, boolean audioOnly) {
        this.label = label;
        this.sub = sub;
        this.sizeBytes = sizeBytes;
        this.height = height;
        this.audioOnly = audioOnly;
    }

    public String sizeText() {
        if (sizeBytes <= 0) {
            return "—";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format("%.0f KB", sizeBytes / 1024.0);
        }
        double mb = sizeBytes / (1024.0 * 1024.0);
        if (mb >= 1024) {
            return String.format("%.1f GB", mb / 1024.0);
        }
        if (mb >= 100) {
            return String.format("%.0f MB", mb);
        }
        return String.format("%.1f MB", mb);
    }
}
