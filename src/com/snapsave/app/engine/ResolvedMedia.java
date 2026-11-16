package com.snapsave.app.engine;

import com.snapsave.app.model.StreamOption;

import java.util.ArrayList;
import java.util.List;

/** Result of resolving a page URL into downloadable streams. */
public class ResolvedMedia {
    public String title = "Untitled";
    public String author = "";
    public String pageUrl = "";
    public String videoId;
    public String duration = "";
    public int grad = 0;
    public String thumbUrl;

    /** Non-null when streams must be finalized by a backend (e.g. "fastsaver"): the option
     *  carries a server format and the direct URL is fetched at download time. */
    public String serverName;

    /** Single progressive file (already contains audio+video). */
    public boolean progressive;
    public String directUrl;
    public String directMime = "video/mp4";

    public final List<StreamOption> videoStreams = new ArrayList<StreamOption>();
    public final List<StreamOption> audioStreams = new ArrayList<StreamOption>();

    /** Best audio-only track, used to pair with adaptive video for muxing. */
    public StreamOption bestAudio;

    public boolean hasAnything() {
        return progressive || !videoStreams.isEmpty() || !audioStreams.isEmpty();
    }
}
