package com.snapsave.app.engine;

import com.snapsave.app.model.StreamOption;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** A single download job and its live progress. */
public class DownloadTask {

    public enum State { QUEUED, DOWNLOADING, MERGING, CONVERTING, PUBLISHING, COMPLETED, PAUSED, FAILED, WAITING_NETWORK }

    /** One media part to fetch (a video-only or audio-only stream, or a single progressive file). */
    public static class Part {
        public String url;
        public String referer;
        /** User-Agent the media URL was issued for (googlevideo rejects a mismatched client). */
        public String ua;
        public File temp;
        public long total;
        public long done;
    }

    public String id;
    public String title;
    public String author;
    public int grad;

    /** "video" | "mp3" | "m4a" */
    public String kind = "video";
    /** The mp3 source is a video file (extract-audio row), so a failed transcode keeps ".mp4". */
    public boolean sourceIsVideo;
    public String label = "1080p";
    public String outName;
    public int mp3Bitrate = 128;

    /** Output category is independent of the processing operation in kind. */
    public String mediaType;
    public String mimeType;
    public String warning;
    /** Original selection, retained so a signed media URL can be refreshed. */
    public String sourcePage;
    public String provider;
    public String formatToken;
    public String sourceMime;
    public int formatItag;
    public int formatHeight;
    public int formatBitrate;
    public boolean selectedAudioOnly;
    public boolean selectedProgressive;

    public StreamOption option;
    public List<Part> parts = new ArrayList<Part>();

    public volatile State state = State.QUEUED;
    public volatile double speed;   // bytes/sec
    public volatile String error;
    public long createdAt = System.currentTimeMillis();
    public long completedAt;

    /** v1.10 legacy private output, or API 21-28 public file path. */
    public File outFile;
    /** Canonical published output: a MediaStore row, or a document in the folder chosen in Settings. */
    public String outputUri;
    /** In-progress public entry (MediaStore row, or a part file in the chosen tree); persisted
     *  before copying so recovery can reconcile it. */
    public String pendingUri;
    public long outputSize;
    public String thumbUrl;
    /** Private validated result retained if publication needs retry after a process death. */
    public File readyFile;
    public long publishDone;
    public long publishTotal;
    public transient Http.CancelToken cancelToken = new Http.CancelToken();

    /** Set by {@link DownloadManager#remove}: the worker must stop and delete nothing else. */
    public transient volatile boolean removed;
    /** True when this task was running at the last persist and should auto-resume on startup. */
    public transient boolean recoveredActive;
    public transient volatile boolean workerScheduled;
    public transient volatile boolean restartRequested;
    public transient volatile boolean waitingForNetwork;

    public long total() {
        long t = 0;
        for (Part p : parts) {
            t += p.total;
        }
        return t;
    }

    public long done() {
        long d = 0;
        for (Part p : parts) {
            d += p.done;
        }
        return d;
    }

    public int percent() {
        long t = total();
        if (t <= 0) {
            return state == State.COMPLETED ? 100 : 0;
        }
        long d = done();
        int pc = (int) (d * 100 / t);
        return Math.max(0, Math.min(100, pc));
    }

    public boolean isActive() {
        return state == State.QUEUED || state == State.DOWNLOADING
                || state == State.MERGING || state == State.CONVERTING
                || state == State.PUBLISHING || state == State.WAITING_NETWORK;
    }
}
