package xtr.keymapper.server;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.Method;

import xtr.keymapper.BuildConfig;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.server.windows.TouchpadDataReceiver;
import xtr.keymapper.server.windows.TouchpadDataReceiverKt;
import xtr.keymapper.server.windows.TouchpadInputProcessor;

public class RemoteServiceShell {
    public static void main(String[] args) {
        try {
            RemoteService.loadLibraries();
            Looper.prepareMainLooper();
            RemoteService mService = new RemoteService(getContext());
            mService.startedFromShell = true;
            boolean noLogcat = false;

            boolean launchApp = true;
            for (String arg : args) {
                switch (arg) {
                    case "--wayland-client":
                        mService.isWaylandClient = true;
                        System.out.println("using wayland client");
                        break;
                    case "--no-auto-launch":
                        launchApp = false;
                        break;
                    case "--no-logcat":
                        noLogcat = true;
                        break;
                    case "--touchpad-input-udp-port":
                    case "--touchpad-input-tcp-port":
                    case "--touchpad-input-stdin":
                    case "--logcat":
                    case "--verbose":
                        TouchpadDataReceiverKt.start(args);
                        System.exit(0);
                        break;
                    default:
                        System.out.println("Invalid argument: " + arg);
                        break;
                }
            }
            new ProcessBuilder("setenforce", "0").inheritIO().start();
            ServiceManager.addService("xtmapper", mService);

            new ProcessBuilder("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.SYSTEM_ALERT_WINDOW").inheritIO().start();
            new ProcessBuilder("settings put system alert_window_bypass_low_ram 1".split("\\s+")).inheritIO().start();
            if (!noLogcat)
                new ProcessBuilder("logcat", "-v", "color", "--pid=" + android.os.Process.myPid()).inheritIO().start();

            if (launchApp) new ProcessBuilder("am", "start", "-a", "android.intent.action.MAIN", "-n",
                    new ComponentName(mService.context, MainActivity.class).flattenToString(),
                    "--es", "data",
                    MainActivity.SHELL_INIT).inheritIO().start();


        } catch (Exception e) {
            Log.e(RemoteService.TAG, e.getMessage(), e);
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
            Log.e(RemoteService.TAG, e.getMessage(), e);
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
