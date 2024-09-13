package xtr.keymapper.server;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.ServiceManager;

import java.lang.reflect.Method;

import xtr.keymapper.BuildConfig;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

public class RemoteServiceShell {
    public static void main(String[] args) {
        try {
            System.out.println("Waiting for overlay...");
            new ProcessBuilder("logcat", "-v", "color", "--pid=" + android.os.Process.myPid()).inheritIO().start();
            new ProcessBuilder("setenforce", "0").inheritIO().start();
            RemoteService.loadLibraries();
            Looper.prepareMainLooper();
            RemoteService mService = new RemoteService(getContext());

            int width = 0, height = 0;
            boolean launchApp = true;
            for (String arg: args) {
                if (arg.equals("--wayland-client")) {
                    mService.isWaylandClient = true;
                    System.out.println("using wayland client");
                    mService.start_getevent();
                } else if (arg.equals("--tcpip")) {
                    mService.start_getevent();
                    System.out.println("using tcpip");
                    new RemoteServiceSocketServer(mService);
                } else if (arg.equals("--no-auto-launch")) {
                    launchApp = false;
                } else if (mService.isWaylandClient) {
                    String[] wh = arg.split("=");
                    if (arg.startsWith("--width"))
                        width = Integer.parseInt(wh[1]);
                    else if (arg.startsWith("--height"))
                        height = Integer.parseInt(wh[1]);
                    else 
                        System.out.println("Invalid argument: " + arg);
                }
            }
            if (width > 0 && height > 0) mService.startServer(new KeymapProfile(), new KeymapConfig(getContext()), null, width, height);

            ServiceManager.addService("xtmapper", mService);
            new ProcessBuilder("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.SYSTEM_ALERT_WINDOW").inheritIO().start();
            new ProcessBuilder("settings put system alert_window_bypass_low_ram 1".split("\\s+")).inheritIO().start();

            if (launchApp) new ProcessBuilder("am", "start", "-a", "android.intent.action.MAIN", "-n",
                    new ComponentName(mService.context, MainActivity.class).flattenToString(),
                    "--es", "data",
                    MainActivity.SHELL_INIT).inheritIO().start();


        } catch (Exception e) {
            e.printStackTrace(System.out);
            System.exit(1);
        }
        Looper.loop();
    }

    public static Context getContextImpl(Context context) {
        while (context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context;
    }

    public static Context getContext() {
        Context systemContext = getSystemContext();
        Context context = null;
        int flags = Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY;
        try {
            context = systemContext.createPackageContext(BuildConfig.APPLICATION_ID, flags);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace(System.err);
        }
        return getContextImpl(context);
    }

    @SuppressLint("PrivateApi")
    static Context getSystemContext() {
        try {
            Class<?> atClazz = Class.forName("android.app.ActivityThread");
            Method systemMain = atClazz.getMethod("systemMain");
            Object activityThread = systemMain.invoke(null);
            Method getSystemContext = atClazz.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
