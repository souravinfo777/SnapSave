package com.snapsave.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.NativeStreams;
import com.snapsave.app.engine.ResolvedMedia;
import com.snapsave.app.engine.Resolvers;
import com.snapsave.app.model.StreamOption;
import com.snapsave.app.util.ImageLoader;
import com.snapsave.app.util.Util;
import com.snapsave.app.util.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DownloadSheet extends Dialog {

    public interface OnDismiss {
        void onDismiss();
    }

    private final ResolvedMedia media;
    private final String title;
    private final String author;
    private final int grad;

    private final List<StreamOption> options = new ArrayList<StreamOption>();
    private int selected = 0;
    private boolean dismissed = false;
    /** Set while a task is being created, so a double tap cannot submit two downloads. */
    private boolean submitting = false;

    private LinearLayout videoBox;
    private LinearLayout audioBox;
    private TextView downloadLabel;
    private OnDismiss onDismiss;

    public static DownloadSheet create(Context c, ResolvedMedia r, String title, String author,
                                       int grad, boolean audioFirst, String thumbUrl) {
        if (r == null || !r.hasAnything()) {
            return null;
        }
        return new DownloadSheet(c, r, title, author, grad, audioFirst, thumbUrl);
    }

    public static void open(Activity a, ResolvedMedia r, String title, String author, int grad,
                            boolean audioFirst) {
        DownloadSheet s = create(a, r, title, author, grad, audioFirst, r == null ? null : r.thumbUrl);
        if (s == null) {
            Toast.makeText(a, "Nothing to download for this item.", Toast.LENGTH_SHORT).show();
            return;
        }
        s.show();
    }

    private DownloadSheet(Context context, ResolvedMedia r, String title, String author, int grad,
                          boolean audioFirst, String thumbUrl) {
        super(context, R.style.Theme_SnapSave_Dialog);
        this.media = r;
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.grad = grad;

        setContentView(R.layout.sheet_download_options);

        videoBox = (LinearLayout) findViewById(R.id.video_options);
        audioBox = (LinearLayout) findViewById(R.id.audio_options);
        downloadLabel = (TextView) findViewById(R.id.sheet_download_label);

        // header: thumbnail + title
        View header = findViewById(R.id.sheet_header);
        String thumb = thumbUrl != null ? thumbUrl : r.thumbUrl;
        if (thumb != null || !this.title.isEmpty()) {
            header.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.sheet_title)).setText(this.title);
            ImageLoader.load((ImageView) findViewById(R.id.sheet_thumb), thumb, Util.grad(grad));
        }

        List<StreamOption> videos = new ArrayList<StreamOption>();
        List<StreamOption> audios = new ArrayList<StreamOption>();
        for (StreamOption o : r.videoStreams) {
            if (usable(o)) videos.add(o);
        }
        for (StreamOption o : r.audioStreams) {
            if (usable(o)) audios.add(o);
        }
        Collections.sort(videos, new Comparator<StreamOption>() {
            @Override
            public int compare(StreamOption a, StreamOption b) {
                return b.height - a.height;
            }
        });
        Collections.sort(audios, new Comparator<StreamOption>() {
            @Override
            public int compare(StreamOption a, StreamOption b) {
                return b.bitrate - a.bitrate;
            }
        });
        for (int i = 0; i < videos.size(); i++) {
            labelVideo(videos.get(i), i);
        }

        int preferredBitrate = Prefs.mp3Bitrate();
        long mp3Size = audioEstimate(r.duration, preferredBitrate);
        if (NativeStreams.canMp3() && r.bestAudio != null && r.bestAudio.url != null) {
            StreamOption mp3 = new StreamOption("MP3 • " + preferredBitrate + " kbps", "Convert audio", mp3Size, 0, true);
            mp3.url = r.bestAudio.url;
            mp3.mimeType = "audio/mpeg";
            mp3.bitrate = preferredBitrate;
            mp3.itag = r.bestAudio.itag;
            mp3.sourceMimeType = r.bestAudio.mimeType;
            mp3.sourceBitrate = r.bestAudio.bitrate;
            audios.add(0, mp3);
        }

        // No audio track anywhere? Offer an MP3 by pulling the sound out of the best video
        // locally with the SnapTube native engine.
        if (!hasAudio(audios) && NativeStreams.canMp3() && !videos.isEmpty()
                && videos.get(0).url != null && videos.get(0).progressive) {
            StreamOption source = videos.get(0);
            StreamOption mp3 = new StreamOption("MP3 • " + preferredBitrate + " kbps", "Extract audio", mp3Size, 0, true);
            mp3.url = source.url;
            mp3.mimeType = "audio/mpeg";
            mp3.bitrate = preferredBitrate;
            mp3.itag = source.itag;
            mp3.sourceMimeType = source.mimeType;
            mp3.sourceBitrate = source.bitrate;
            mp3.height = source.height;
            mp3.progressive = source.progressive;
            // The source is a video container: if the transcode fails, the fallback must keep the
            // real container extension instead of guessing from an audio extension list.
            mp3.sourceIsVideo = true;
            audios.add(mp3);
        }

        options.addAll(videos);
        options.addAll(audios);

        render();

        findViewById(R.id.video_section_title)
                .setVisibility(videoBox.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        findViewById(R.id.audio_section_title)
                .setVisibility(audioBox.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        select(defaultSelection(audioFirst));
        probeMissingSizes();

        findViewById(R.id.sheet_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.sheet_download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload();
            }
        });
    }

    private int defaultSelection(boolean audioFirst) {
        int fallback = 0;
        if (audioFirst) {
            boolean found = false;
            String preferred = Prefs.audioFormat();
            for (int i = 0; i < options.size(); i++) {
                StreamOption option = options.get(i);
                if (!option.audioOnly) continue;
                if (!found) { fallback = i; found = true; }
                if (("mp3".equals(preferred) && ".mp3".equals(audioExt(option)))
                        || ("m4a".equals(preferred) && ".m4a".equals(audioExt(option)))) return i;
            }
            return fallback;
        }
        int quality = Prefs.videoQuality();
        int smallest = -1;
        for (int i = 0; i < options.size(); i++) {
            StreamOption option = options.get(i);
            if (option.audioOnly) continue;
            if (smallest < 0 || option.height < options.get(smallest).height) smallest = i;
            if (quality == 0 || (option.height > 0 && option.height <= quality)) return i;
        }
        return smallest < 0 ? fallback : smallest;
    }

    public void setOnDismiss(OnDismiss l) {
        this.onDismiss = l;
    }

    @Override
    public void onBackPressed() {
        dismiss();
    }

    @Override
    public void dismiss() {
        super.dismiss();
        if (!dismissed) {
            dismissed = true;
            if (onDismiss != null) {
                onDismiss.onDismiss();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window w = getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setAttributes(lp);
        }
    }

    /**
     * The sheet opens instantly; for the rows the backend left without a size, measure the real
     * file length in the background and refresh the label. Audio rows are skipped — their size
     * depends on the local transcode, not the source file.
     */
    private void probeMissingSizes() {
        final List<StreamOption> pending = new ArrayList<StreamOption>();
        for (StreamOption o : options) {
            if (!o.audioOnly && o.sizeBytes <= 0 && o.url != null && o.url.startsWith("http")) {
                pending.add(o);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (final StreamOption o : pending) {
                    final long size = Resolvers.probeSize(o.url);
                    if (size > 0) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (dismissed) {
                                    return;
                                }
                                o.sizeBytes = size;
                                render();
                                select(selected);
                            }
                        });
                    }
                }
            }
        }, "size-probe").start();
    }

    private void render() {
        videoBox.removeAllViews();
        audioBox.removeAllViews();
        for (int i = 0; i < options.size(); i++) {
            StreamOption o = options.get(i);
            LinearLayout target = o.audioOnly ? audioBox : videoBox;
            View row = LayoutInflater.from(getContext()).inflate(R.layout.item_option, target, false);
            ((TextView) row.findViewById(R.id.option_title)).setText(o.label);
            ((TextView) row.findViewById(R.id.option_sub)).setText(o.sub);
            ((TextView) row.findViewById(R.id.option_size)).setText(o.sizeText());
            final int idx = i;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                }
            });
            target.addView(row);
        }
    }

    private void select(int index) {
        if (options.isEmpty()) {
            return;
        }
        selected = index;
        updateRows(videoBox);
        updateRows(audioBox);
        StreamOption o = options.get(selected);
        downloadLabel.setText("Download • " + o.label + " • " + o.sizeText());
    }

    private void updateRows(LinearLayout box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View row = box.getChildAt(i);
            int global = globalIndex(box, i);
            boolean on = global == selected;
            row.setBackgroundResource(on ? R.drawable.bg_option_selected : R.drawable.bg_option);
            row.findViewById(R.id.option_dot).setBackgroundResource(on ? R.drawable.bg_badge : R.drawable.bg_icon_circle);
        }
    }

    private int globalIndex(LinearLayout box, int child) {
        int count = 0;
        for (int i = 0; i < options.size(); i++) {
            LinearLayout target = options.get(i).audioOnly ? audioBox : videoBox;
            if (target == box) {
                if (count == child) return i;
                count++;
            }
        }
        return -1;
    }

    private void startDownload() {
        startDownload(false);
    }

    /**
     * @param resolvedUrl the caller already fetched the backend's signed URL, so the lazy-format
     *                    branch must be skipped instead of doing the same network call twice.
     */
    private void startDownload(boolean resolvedUrl) {
        if (options.isEmpty() || dismissed || (submitting && !resolvedUrl)) {
            return;   // one tap, one task — double taps must not queue duplicates
        }
        submitting = true;
        final StreamOption o = options.get(selected);

        // Backend formats (FastSaver) resolve to a signed URL only at download time.
        if (!resolvedUrl && (o.url == null || o.url.isEmpty()) && o.serverFormat != null
                && media.pageUrl != null) {
            Toast.makeText(getContext(), "Preparing download...", Toast.LENGTH_SHORT).show();
            final String pageUrl = media.pageUrl;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String du = com.snapsave.app.engine.FastSaver.downloadUrl(pageUrl, o.serverFormat);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (dismissed) {
                                return;
                            }
                            if (du == null) {
                                submitting = false;
                                Toast.makeText(getContext(), "Could not prepare this download.", Toast.LENGTH_LONG).show();
                            } else {
                                o.url = du;
                                startDownload(true);   // bypasses the submitting guard
                            }
                        }
                    });
                }
            }, "resolve-url").start();
            return;
        }
        if (o.url == null || o.url.isEmpty()) {
            submitting = false;
            Toast.makeText(getContext(), "No download URL for this option.", Toast.LENGTH_SHORT).show();
            return;
        }

        DownloadTask t = new DownloadTask();
        t.title = clean(title, o);
        t.author = author;
        t.grad = grad;
        t.option = o;
        t.label = o.label;
        t.sourceIsVideo = o.sourceIsVideo;
        t.sourcePage = media.pageUrl;
        t.provider = media.serverName;
        t.formatToken = o.serverFormat;
        t.formatItag = o.itag;
        t.formatHeight = o.height;
        t.formatBitrate = o.sourceMimeType != null ? o.sourceBitrate : o.bitrate;
        t.sourceMime = o.sourceMimeType != null ? o.sourceMimeType : o.mimeType;
        t.selectedAudioOnly = o.audioOnly;
        t.selectedProgressive = o.progressive;
        t.mimeType = o.mimeType;
        t.mediaType = o.audioOnly ? "audio" : (o.mimeType != null && o.mimeType.startsWith("image/") ? "image" : "video");

        DownloadTask.Part part = new DownloadTask.Part();
        part.url = o.url;
        if (isYouTubePage()) {
            part.referer = media.pageUrl;
            if (media.serverName == null) {
                part.ua = Resolvers.YT_MEDIA_UA;   // googlevideo binds the URL to this client
            }
        }

        boolean image = o.mimeType != null && o.mimeType.startsWith("image");
        if (image) {
            t.kind = "file";
            t.outName = clean(title, o) + imageExt(o);
            t.parts.add(part);
        } else if (o.serverFormat != null) {
            if (o.audioOnly) {
                boolean m4a = "m4a".equals(o.serverFormat);
                t.kind = "file";
                t.outName = clean(title, o) + (m4a ? ".m4a" : ".mp3");
            } else {
                t.kind = "video";
                t.outName = clean(title, o) + videoExt(o);
            }
            t.parts.add(part);
        } else if (o.audioOnly) {
            String ext = audioExt(o);
            if (".mp3".equals(ext)) {
                t.kind = "mp3";
                t.mp3Bitrate = o.bitrate > 0 ? o.bitrate : 192;
            } else if (".m4a".equals(ext) || ".aac".equals(ext)) {
                t.kind = "m4a";
            } else {
                t.kind = "file";            // opus / flac / wav / ogg: copy untouched
            }
            t.outName = clean(title, o) + ext;
            t.parts.add(part);
        } else if (o.progressive || media.bestAudio == null || media.bestAudio.url == null) {
            t.kind = "video";
            t.outName = clean(title, o) + videoExt(o);
            t.parts.add(part);
        } else {
            // Video-only stream + separate audio track: download both and mux.
            t.kind = "video";
            t.outName = clean(title, o) + videoExt(o);
            t.parts.add(part);
            DownloadTask.Part audio = new DownloadTask.Part();
            audio.url = media.bestAudio.url;
            if (isYouTubePage()) {
                audio.referer = media.pageUrl;
                if (media.serverName == null) {
                    audio.ua = Resolvers.YT_MEDIA_UA;
                }
            }
            t.parts.add(audio);
        }

        App.get().downloads().submit(t);
        Toast.makeText(getContext(), "Download started • " + o.label, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private boolean isYouTubePage() {
        return media.pageUrl != null && media.pageUrl.contains("youtu");
    }

    /** Give a video row the quality label the sheet is designed around (1080p Full HD, …). */
    private static void labelVideo(StreamOption o, int rank) {
        String l = o.label == null ? "" : o.label.trim();
        boolean generic = l.isEmpty() || "Original".equalsIgnoreCase(l)
                || "Video".equalsIgnoreCase(l) || "Progressive".equalsIgnoreCase(l);
        if (l.matches("\\d+")) {
            l = l + "p";                       // "1080" -> "1080p"
        }
        if (l.matches("\\d+p") && o.height > 0) {
            l = l + qualitySuffix(o.height);     // "1080p" -> "1080p Full HD"
        } else if (generic && o.height > 0) {
            l = o.height + "p" + qualitySuffix(o.height);
        }
        o.label = l.isEmpty() ? "Original" : l;

        if (o.sub == null || o.sub.trim().isEmpty() || o.sub.toLowerCase().contains("server")) {
            o.sub = subFor(rank);
        }
    }

    private static String qualitySuffix(int h) {
        if (h >= 2160) return " 4K";
        if (h >= 1440) return " 2K";
        if (h >= 1080) return " Full HD";
        if (h >= 720) return " HD";
        return "";
    }

    private static String subFor(int rank) {
        switch (rank) {
            case 0: return "MP4 • Best quality";
            case 1: return "MP4 • Recommended";
            case 2: return "MP4 • Saves data";
            case 3: return "MP4 • Small size";
            default: return "MP4 • Video";
        }
    }

    /** Selectable when it has a direct URL or a lazy backend format to resolve at download time. */
    private static boolean usable(StreamOption o) {
        return o != null && ((o.url != null && !o.url.isEmpty()) || o.serverFormat != null);
    }

    private static boolean hasAudio(List<StreamOption> opts) {
        for (StreamOption o : opts) {
            if (o.audioOnly) {
                return true;
            }
        }
        return false;
    }

    /** File extension for an image stream — never force a PNG/WebP into ".jpg". */
    private static String imageExt(StreamOption o) {
        String m = o.mimeType == null ? "" : o.mimeType.toLowerCase();
        String u = o.url == null ? "" : o.url.toLowerCase();
        if (m.contains("png") || u.contains(".png")) return ".png";
        if (m.contains("webp") || u.contains("webp")) return ".webp";
        if (m.contains("gif") || u.contains(".gif")) return ".gif";
        return ".jpg";
    }

    /** Container extension for a video stream, based on MIME/URL rather than assuming MP4. */
    private static String videoExt(StreamOption o) {
        String m = o.mimeType == null ? "" : o.mimeType.toLowerCase();
        String u = o.url == null ? "" : o.url.toLowerCase();
        if (m.contains("webm") || u.contains(".webm")) return ".webm";
        if (m.contains("matroska") || u.contains(".mkv")) return ".mkv";
        if (m.contains("quicktime") || u.contains(".mov")) return ".mov";
        if (u.contains(".3gp")) return ".3gp";
        return ".mp4";
    }

    /** Container extension for an audio stream. */
    private static String audioExt(StreamOption o) {
        String m = o.mimeType == null ? "" : o.mimeType.toLowerCase();
        String u = o.url == null ? "" : o.url.toLowerCase();
        if (m.contains("mpeg") || u.contains(".mp3")) return ".mp3";
        if (u.contains(".aac") || m.contains("aac")) return ".aac";
        if (u.contains(".opus") || m.contains("opus")) return ".opus";
        if (u.contains(".flac") || m.contains("flac")) return ".flac";
        if (u.contains(".wav") || m.contains("wav")) return ".wav";
        if (u.contains(".ogg") || m.contains("ogg")) return ".ogg";
        if (u.contains(".webm") || m.contains("webm")) return ".webm";
        return ".m4a";
    }

    /** Estimated byte size of an MP3 at {@code kbps} for a "m:ss" duration; 0 when unknown. */
    private static long audioEstimate(String duration, int kbps) {
        if (duration == null || kbps <= 0) {
            return 0;
        }
        try {
            int secs = 0;
            for (String part : duration.trim().split(":")) {
                secs = secs * 60 + Integer.parseInt(part.trim());
            }
            return secs > 0 ? (long) kbps * 1000 * secs / 8 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String clean(String s, StreamOption o) {
        // Never let the literal "null" (from {"title": null}) or a raw link become the file name.
        String base = Resolvers.firstText(s);
        if (base == null || base.startsWith("http://") || base.startsWith("https://")) {
            base = "SnapSave_" + System.currentTimeMillis();
        }
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) {
            base = "SnapSave_" + System.currentTimeMillis();
        }
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base;
    }
}