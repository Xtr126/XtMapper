package xtr.keymapper.wayland;

import android.os.ParcelFileDescriptor;

import java.io.IOException;

/** Owns a connected compositor descriptor and its native dispatch thread. */
public final class WaylandCaptureNative implements AutoCloseable {
    public static final int CONNECTED = 1;
    public static final int MAPPED = 1 << 1;
    public static final int KEYBOARD_FOCUS = 1 << 2;
    public static final int POINTER_FOCUS = 1 << 3;
    public static final int POINTER_LOCKED = 1 << 4;
    public static final int RELATIVE_POINTER_AVAILABLE = 1 << 5;

    public static final int KEY = 1;
    public static final int BUTTON = 2;
    public static final int ABSOLUTE = 3;
    public static final int RELATIVE = 4;
    public static final int SCROLL = 5;

    public interface Callback {
        // Invoked on the dispatch thread. Post to the service handler without blocking.
        void onState(long generation, int state);
        void onEvent(long generation, int type, int code, double x, double y);
    }

    private long session;

    public WaylandCaptureNative(int fd, Callback callback) {
        try {
            System.loadLibrary("xtmapper_wayland");
        } catch (LinkageError error) {
            if (fd >= 3) {
                try {
                    ParcelFileDescriptor.adoptFd(fd).close();
                } catch (IOException ignored) {
                    // The load error is the reason the backend could not start.
                }
            }
            throw error;
        }
        session = start(fd, callback);
        if (session == 0) throw new IllegalStateException("Cannot start Wayland capture");
    }

    public synchronized void setVisible(
            boolean visible, long generation, boolean lockPointer, int width, int height) {
        if (session != 0) request(session, visible, generation, lockPointer, width, height);
    }

    @Override
    public synchronized void close() {
        if (session == 0) return;
        stop(session);
        session = 0;
    }

    private static native long start(int fd, Callback callback);
    private static native void request(long session, boolean visible, long generation,
            boolean lockPointer, int width, int height);
    private static native void stop(long session);
}
