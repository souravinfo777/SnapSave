package com.snaptube.taskManager;

/**
 * JNI bridge to SnapTube's native media engine libvideo_v1.so (FFmpeg-based muxer).
 *
 * The package, class name, native method name/signature and the native->Java
 * callback method MUST match what the shipped .so was compiled against, otherwise
 * JNI lookup fails. Verified against the decompiled original:
 *   private native int combvideo(String audio, String video, String output);
 *   public void onProgress(int cur, int total);   // invoked from native
 *   LIB_NAME = "video_v1"
 *
 * Error codes are the original ones exposed by the engine.
 */
public class CombinTask implements Runnable {

    public static final int COMB_SUCCESS = 0;
    public static final int COMB_NATIVE_CRASH = -1;
    public static final int AUDIO_INPUTFILE_OPEN_ERROR = -2;
    public static final int VIDEO_INPUTFILE_OPEN_ERROR = -3;
    public static final int AUDIO_INPUT_FORMAT_ERROR = -4;
    public static final int VIDEO_INPUT_FORMAT_ERROR = -5;
    public static final int ALLOC_CONTEXT_ERROR = -6;
    public static final int ALLOC_STREAM_ERROR = -7;
    public static final int COPY_CONTEXT_ERROR = -8;
    public static final int CREATE_FILE_ERROR = -9;
    public static final int WRITE_HEADER_ERROR = -10;
    public static final int WRITE_PACKET_ERROR = -11;
    public static final int WRITE_TRAILER_ERROR = -12;

    private static final String LIB_NAME = "video_v1";
    private static boolean supported = false;

    private final String audioFilePath;
    private final String videoFilePath;
    private final String outputFilePath;

    private volatile Listener listener;
    private volatile boolean canceled = false;

    public interface Listener {
        void onFinish(int code);
        void onProgress(int current, int total);
    }

    static {
        try {
            System.loadLibrary(LIB_NAME);
            supported = true;
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public CombinTask(String audioFilePath, String videoFilePath, String outputFilePath) {
        this.audioFilePath = audioFilePath;
        this.videoFilePath = videoFilePath;
        this.outputFilePath = outputFilePath;
    }

    private native int combvideo(String audio, String video, String output);

    public static boolean isSupported() {
        return supported;
    }

    /** Invoked by the native engine to report mux progress. */
    public void onProgress(int current, int total) {
        Listener l = this.listener;
        if (l != null) {
            l.onProgress(current, total);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void cancel() {
        this.canceled = true;
    }

    @Override
    public void run() {
        synchronized (CombinTask.class) {
            if (canceled) {
                return;
            }
            int code;
            try {
                code = combvideo(this.audioFilePath, this.videoFilePath, this.outputFilePath);
            } catch (Throwable th) {
                th.printStackTrace();
                code = COMB_NATIVE_CRASH;
            }
            Listener l = this.listener;
            if (l != null) {
                l.onFinish(code);
            }
        }
    }
}
