package xtr.keymapper.server;


import android.os.Looper;
import android.os.ServiceManager;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

public class RemoteServiceShell {
    public static void main(String[] args) {
        RemoteService.loadLibraries();
        Looper.prepareMainLooper();
        RemoteService mService = new RemoteService();
        try {
            System.out.println("Waiting for overlay...");
            int width = 0, height = 0;
            for (String arg: args) {
                if (arg.equals("--wayland-client")) {
                    mService.isWaylandClient = true;
                    System.out.println("using wayland client");
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
            if (width > 0 && height > 0) mService.inputService = new InputService(new KeymapProfile(), new KeymapConfig(null), null, width, height, null, true);
            ServiceManager.addService("xtmapper", mService);
            mService.start_getevent();
        } catch (Exception e) {
            e.printStackTrace(System.out);
            System.exit(1);
        }
        Looper.loop();
    }
}
