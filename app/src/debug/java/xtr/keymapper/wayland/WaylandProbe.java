package xtr.keymapper.wayland;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;

/** Standalone debug app_process entry point for the Waydroid feasibility gate. */
public final class WaylandProbe {
    private static final String TAG = "xtmapper-wayland-probe";

    private WaylandProbe() {}

    public static void main(String[] args) {
        String endpoint = resolveEndpoint(
                SystemProperties.get("waydroid.xdg_runtime_dir", ""),
                SystemProperties.get("waydroid.wayland_display", ""));
        int result = endpoint == null ? 2 : run(endpoint, 10_000);
        String summary = "result=" + result + " endpoint=" + endpoint;
        Log.i(TAG, summary);
        System.out.println(summary);
        System.exit(result);
    }

    static String resolveEndpoint(String runtime, String display) {
        if (display.trim().isEmpty() || display.equals("None")) return null;
        if (display.startsWith("/")) return display;
        if (!runtime.startsWith("/") || display.contains("/")
                || display.equals(".") || display.equals("..")) return null;
        return new File(runtime, display).getPath();
    }

    static int run(String endpoint, int visibleMillis) {
        System.loadLibrary("wayland_probe");
        return probe(endpoint, visibleMillis);
    }

    private static native int probe(String endpoint, int visibleMillis);
}
