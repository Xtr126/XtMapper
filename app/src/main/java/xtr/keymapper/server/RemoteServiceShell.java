package xtr.keymapper.server;


import android.os.Looper;
import android.os.ServiceManager;

public class RemoteServiceShell {
    public static void main(String[] args) {
        RemoteService.loadLibraries();
        Looper.prepareMainLooper();
        RemoteService mService = new RemoteService();
        try {
            System.out.println("Waiting for overlay...");
            for (String arg: args) {
                if (arg.equals("--wayland-client")) {
                    mService.isWaylandClient = true;
                    System.out.println("using wayland client");
                }
                if (arg.equals("--tcpip")) {
                    mService.start_getevent();
                    System.out.println("using tcpip");
                    new RemoteServiceSocketServer(mService);
                }
            }
            ServiceManager.addService("xtmapper", mService);
            mService.start_getevent();
        } catch (Exception e) {
            e.printStackTrace(System.out);
            System.exit(1);
        }
        Looper.loop();
    }
}
