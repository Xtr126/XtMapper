package xtr.keymapper.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.ServiceManager;

import java.lang.reflect.Method;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

public class RemoteServiceShell {
    public static void main(String[] args) {
        RemoteService.loadLibraries();
        Looper.prepareMainLooper();
        RemoteService mService = new RemoteService(getContext());
        try {
            System.out.println("Waiting for overlay...");
            int width = 0, height = 0;
            for (String arg: args) {
                if (arg.equals("--wayland-client")) {
                    mService.isWaylandClient = true;
                    System.out.println("using wayland client");
                    mService.start_getevent();
                } else if (arg.equals("--tcpip")) {
                    mService.start_getevent();
                    System.out.println("using tcpip");
                    new RemoteServiceSocketServer(mService);
                } else if (mService.isWaylandClient) {
                    String[] wh = arg.split("=");
                    if (arg.startsWith("--width"))
                        width = Integer.parseInt(wh[1]);
                    else if (arg.startsWith("--height"))
                        height = Integer.parseInt(wh[1]);
                }
            }
            if (width > 0 && height > 0) mService.startServer(new KeymapProfile(), new KeymapConfig(getContext()), null, width, height);
            ServiceManager.addService("xtmapper", mService);
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
            context = systemContext.createPackageContext(xtr.keymapper.R.class.getPackage().getName(), flags);
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
