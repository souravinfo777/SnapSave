package com.snaptube.taskManager;

/**
 * JNI bridge to SnapTube's native libmp3cvt.so DASH helpers.
 *
 * Verified signatures from the original:
 *   private static native long convertDASH2MPEG(String in, String out);
 *   public  static native boolean isDashFormat(String path);
 *   LIB_NAME = "mp3cvt"
 *
 * The original also exposes M4A metadata read/write that returns
 * androidx MediaMetadataCompat.Builder objects; those are intentionally omitted here
 * (omitting unused native methods is safe: JNI resolves them lazily on first call).
 */
public class M4ANativeLib {

    private static final String LIB_NAME = "mp3cvt";
    private static boolean supported = false;

    static {
        try {
            System.loadLibrary(LIB_NAME);
            supported = true;
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static native long convertDASH2MPEG(String in, String out);

    public static native boolean isDashFormat(String path);

    public static boolean isSupported() {
        return supported;
    }

    /** Convert a DASH/fragmented MP4 audio file into a plain MPEG container. Returns true on success. */
    public static boolean convertDASHM4A(String in, String out) {
        if (!supported || in == null || out == null || in.isEmpty() || out.isEmpty() || in.equals(out)) {
            return false;
        }
        try {
            if (!isDashFormat(in)) {
                return true;
            }
            return convertDASH2MPEG(in, out) == 0;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }
}
