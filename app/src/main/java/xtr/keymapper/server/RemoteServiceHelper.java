package xtr.keymapper.server;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;

import xtr.keymapper.IRemoteService;

public class RemoteServiceHelper {

    private static IRemoteService service = null;

    public static void pauseKeymap(){
        IRemoteService mService = getInstance();
        if (mService != null) try {
            mService.pauseMouse();
        } catch (RemoteException e) {
            Log.i("RemoteService", e.toString());
        }
    }

    public static void resumeKeymap(){
        IRemoteService mService = getInstance();
        if (mService != null) try {
            mService.resumeMouse();
        } catch (RemoteException e) {
            Log.i("RemoteService", e.toString());
        }
    }

    public static void reloadKeymap() {
        IRemoteService mService = getInstance();
        if (mService != null) try {
            mService.reloadKeymap();
        } catch (RemoteException e) {
            Log.i("RemoteService", e.toString());
        }
    }

    public static IRemoteService getInstance(){
        if (service == null) {
            // Try tcpip connection first
            try {
                service = new RemoteServiceSocketClient();
            } catch (IOException e) {
                Log.e(e.toString(), e.getMessage(), e);
                RemoteServiceSocketClient.socket = null;
            }
            if (RemoteServiceSocketClient.socket == null) {
                service = IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"));
                if (service != null) try {
                    service.asBinder().linkToDeath(() -> service = null, 0);
                } catch (RemoteException ignored) {
                }
            }
        }
        return service;
    }
}
