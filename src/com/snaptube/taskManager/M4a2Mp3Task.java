package com.snaptube.taskManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * JNI bridge to SnapTube's native libmp3cvt.so (M4A -> MP3 transcoder).
 *
 * Verified signatures from the original:
 *   private native void cancelConvert(long token);
 *   private native boolean convertM4aToMp3(long token, String m4a, String mp3, int bitrate);
 *   public void onConvertProgress(int percent);            // invoked from native
 *   public void onConvertComplete(int,int,int,int,int,int); // invoked from native
 *   LIB_NAME = "mp3cvt"
 */
public class M4a2Mp3Task implements Runnable {

    public static final int MP3_PREFER_BITRATE = 128;

    private static final String LIB_NAME = "mp3cvt";
    private static final AtomicLong TOKENS = new AtomicLong(1);
    private static boolean supported = false;

    private final String m4aFilePath;
    private final String mp3FilePath;
    private final int bitrate;
    private final long token = TOKENS.getAndIncrement();

    private volatile Listener listener;
    private volatile boolean canceled = false;

    public interface Listener {
        void onFinish(boolean success);
        void onProgress(int percent);
    }

    static {
        try {
            System.loadLibrary(LIB_NAME);
            supported = true;
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public M4a2Mp3Task(String m4aFilePath, String mp3FilePath, int bitrate) {
        this.m4aFilePath = m4aFilePath;
        this.mp3FilePath = mp3FilePath;
        this.bitrate = bitrate;
    }

    private native void cancelConvert(long token);

    private native boolean convertM4aToMp3(long token, String m4a, String mp3, int bitrate);

    public static boolean isSupported() {
        return supported;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void cancel() {
        this.canceled = true;
        try {
            cancelConvert(this.token);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** Invoked by the native engine to report conversion progress. */
    public void onConvertProgress(int percent) {
        Listener l = this.listener;
        if (l != null) {
            l.onProgress(percent);
        }
    }

    /** Invoked by the native engine on completion. */
    public void onConvertComplete(int a, int b, int c, int d, int e, int f) {
        // no-op
    }

    @Override
    public void run() {
        synchronized (M4a2Mp3Task.class) {
            if (canceled) {
                return;
            }
            boolean ok;
            try {
                ok = convertM4aToMp3(this.token, this.m4aFilePath, this.mp3FilePath, this.bitrate);
            } catch (Throwable th) {
                th.printStackTrace();
                ok = false;
            }
            Listener l = this.listener;
            if (l != null) {
                l.onFinish(ok);
            }
        }
    }
}
