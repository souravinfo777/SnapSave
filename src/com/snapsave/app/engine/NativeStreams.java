package com.snapsave.app.engine;

import com.snaptube.taskManager.CombinTask;
import com.snaptube.taskManager.M4ANativeLib;
import com.snaptube.taskManager.M4a2Mp3Task;

import java.io.File;

/**
 * Thin wrappers around SnapTube's native media engine (libvideo_v1.so / libmp3cvt.so).
 * These are the same native entry points the original app used to combine video+audio
 * and to transcode audio to MP3.
 */
public final class NativeStreams {

    private NativeStreams() {
    }

    /** True when both native engine libraries loaded successfully. */
    public static boolean available() {
        return canMux() && canMp3();
    }

    /** The muxer (libvideo_v1.so) is loaded — independent of the audio transcoder. */
    public static boolean canMux() {
        return CombinTask.isSupported();
    }

    /** The M4A→MP3 transcoder (libmp3cvt.so) is loaded. */
    public static boolean canMp3() {
        return M4a2Mp3Task.isSupported();
    }

    /** The DASH→MPEG helper lives in the same library as the MP3 transcoder. */
    public static boolean canDashM4a() {
        return M4ANativeLib.isSupported();
    }

    /**
     * Combine a video-only stream and an audio-only stream into one MP4 container using
     * SnapTube's native FFmpeg muxer (CombinTask.combvideo).
     */
    public static boolean mux(File audio, File video, File output) {
        if (!CombinTask.isSupported()) {
            return false;
        }
        final boolean[] ok = {false};
        CombinTask task = new CombinTask(audio.getAbsolutePath(), video.getAbsolutePath(), output.getAbsolutePath());
        task.setListener(new CombinTask.Listener() {
            @Override
            public void onFinish(int code) {
                ok[0] = code == CombinTask.COMB_SUCCESS;
            }

            @Override
            public void onProgress(int current, int total) {
            }
        });
        // The caller is already a worker. Keep ownership until native writes have stopped.
        task.run();
        return ok[0];
    }

    /** Transcode an M4A/AAC audio file to MP3 using SnapTube's native transcoder (M4a2Mp3Task). */
    public static boolean toMp3(File input, File output, int bitrate) {
        if (!M4a2Mp3Task.isSupported()) {
            return false;
        }
        final boolean[] ok = {false};
        M4a2Mp3Task task = new M4a2Mp3Task(input.getAbsolutePath(), output.getAbsolutePath(), bitrate);
        task.setListener(new M4a2Mp3Task.Listener() {
            @Override
            public void onFinish(boolean success) {
                ok[0] = success;
            }

            @Override
            public void onProgress(int percent) {
            }
        });
        task.run();
        return ok[0];
    }

    /** Normalize a fragmented (DASH) M4A into a plain MPEG container (M4ANativeLib). */
    public static boolean dashToMpeg(File input, File output) {
        if (!M4ANativeLib.isSupported()) {
            return false;
        }
        try {
            return M4ANativeLib.convertDASHM4A(input.getAbsolutePath(), output.getAbsolutePath());
        } catch (Throwable t) {
            return false;
        }
    }
}
