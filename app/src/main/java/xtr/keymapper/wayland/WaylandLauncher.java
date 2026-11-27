package xtr.keymapper.wayland;

import android.os.SystemProperties;
import android.util.Log;

import java.util.Arrays;

/** Opens the Waydroid compositor socket before starting the shell-UID service. */
public final class WaylandLauncher {
    private static final String TAG = "xtmapper-wayland";

    private WaylandLauncher() {}

    public static void main(String[] args) {
        if (args.length < 2 || !args[0].startsWith("/") || !args[1].startsWith("/")) {
            fail("The launcher needs the installed APK and native-library paths.", 2);
            return;
        }
        String[] serverArgs = Arrays.copyOfRange(args, 2, args.length);
        for (String arg : serverArgs) {
            if (!arg.equals("--no-auto-launch") && !arg.equals("--no-logcat")) {
                fail("Invalid Waydroid launcher argument: " + arg, 2);
                return;
            }
        }
        String endpoint = resolveEndpoint(
                SystemProperties.get("waydroid.xdg_runtime_dir", ""),
                SystemProperties.get("waydroid.wayland_display", ""));
        if (endpoint == null) {
            fail("Waydroid did not provide a valid compositor socket path.", 2);
            return;
        }
        try {
            System.loadLibrary("xtmapper_wayland");
            fail(execServer(endpoint, args[0], args[1], serverArgs), 3);
        } catch (LinkageError error) {
            fail("Cannot load the Waydroid launcher: " + error.getMessage(), 3);
        }
    }

    static String resolveEndpoint(String runtime, String display) {
        if (display.trim().isEmpty() || display.equalsIgnoreCase("None")) return null;
        if (display.startsWith("/")) return validAbsolutePath(display) ? display : null;
        if (!validAbsolutePath(runtime) || display.indexOf('/') >= 0 ||
                display.indexOf('\0') >= 0 || display.equals(".") || display.equals("..")) return null;
        return runtime.endsWith("/") ? runtime + display : runtime + "/" + display;
    }

    private static boolean validAbsolutePath(String path) {
        if (!path.startsWith("/") || path.indexOf('\0') >= 0) return false;
        for (String component : path.split("/")) {
            if (component.equals(".") || component.equals("..")) return false;
        }
        return true;
    }

    private static void fail(String reason, int status) {
        Log.e(TAG, reason);
        System.err.println(reason);
        System.exit(status);
    }

    private static native String execServer(String endpoint, String apk, String nativeLibraries,
            String[] serverArgs);
}
