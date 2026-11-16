package com.snapsave.app.engine;

import com.snapsave.app.model.StreamOption;

/** Refreshes the same selected format; never appends old bytes to a changed URL. */
public final class TaskRefresh {
    private TaskRefresh() { }

    public static boolean refresh(DownloadTask task) throws Exception {
        if (task.sourcePage == null || task.sourcePage.isEmpty() || task.cancelToken.cancelled) return false;
        if ("fastsaver".equals(task.provider) && task.formatToken != null && !task.formatToken.isEmpty()) {
            String url = FastSaver.downloadUrl(task.sourcePage, task.formatToken);
            if (url == null || task.parts.size() != 1) return false;
            checkCancelled(task);
            update(task.parts.get(0), url, task.sourcePage, null);
            return true;
        }
        ResolvedMedia media = Resolvers.resolve(task.sourcePage);
        checkCancelled(task);
        if (media == null) return false;
        StreamOption selected = match(task, media);
        if (selected == null) return false;
        String url = selected.url;
        if ((url == null || url.isEmpty()) && selected.serverFormat != null) {
            url = FastSaver.downloadUrl(task.sourcePage, selected.serverFormat);
        }
        checkCancelled(task);
        if (url == null || url.isEmpty() || task.parts.isEmpty()) return false;
        String ua = Resolvers.isYouTube(task.sourcePage) && media.serverName == null ? Resolvers.YT_MEDIA_UA : null;
        if (task.parts.size() > 1 && (selected.progressive || media.bestAudio == null
                || media.bestAudio.url == null)) return false;
        update(task.parts.get(0), url, task.sourcePage, ua);
        if (task.parts.size() > 1) update(task.parts.get(1), media.bestAudio.url, task.sourcePage, ua);
        return true;
    }

    static StreamOption match(DownloadTask task, ResolvedMedia media) {
        java.util.List<StreamOption> candidates = task.selectedAudioOnly && !task.sourceIsVideo
                ? media.audioStreams : media.videoStreams;
        if (task.sourceIsVideo && task.formatItag > 0) candidates = media.videoStreams;
        for (StreamOption option : candidates) {
            if (task.formatToken != null && !task.formatToken.isEmpty()) {
                if (task.formatToken.equals(option.serverFormat)) return option;
                continue;
            }
            if (task.formatItag > 0) {
                if (option.itag == task.formatItag) return option;
                continue;
            }
            if (option.height != task.formatHeight || option.progressive != task.selectedProgressive) continue;
            if (task.sourceMime != null && !task.sourceMime.equals(option.mimeType)) continue;
            if (task.formatBitrate > 0 && option.bitrate != task.formatBitrate) continue;
            return option;
        }
        return null;
    }

    private static void checkCancelled(DownloadTask task) throws Http.Cancelled {
        if (task.cancelToken.cancelled || task.removed) throw new Http.Cancelled();
    }

    private static void update(DownloadTask.Part part, String url, String referer, String ua) throws Exception {
        // Without an entity validator a newly signed URL does not prove byte identity.
        // Start this part again even if quality/itag matches to avoid corrupting a resume.
        if (!url.equals(part.url)) {
            if (part.temp != null && part.temp.exists() && !part.temp.delete()) {
                throw new java.io.IOException("could_not_reset_partial");
            }
            part.done = 0;
            part.total = 0;
        }
        part.url = url;
        part.referer = referer;
        part.ua = ua;
    }
}
