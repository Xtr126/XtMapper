package xtr.keymapper.server;

import android.os.RemoteException;
import android.util.Log;

import xtr.keymapper.IRemoteService;

public class RemoteServiceHelper {

    public static void pauseKeymap(){
        IRemoteService mService = RemoteService.getInstance();
        if (mService != null) try {
            mService.pauseMouse();
        } catch (RemoteException e) {
            Log.i("RemoteService", e.toString());
        }
    }

    public static void resumeKeymap(){
        IRemoteService mService = RemoteService.getInstance();
        if (mService != null) try {
            mService.resumeMouse();
        } catch (RemoteException e) {
            Log.i("RemoteService", e.toString());
        }
    }

    public static void reloadKeymap() {
        IRemoteService mService = RemoteService.getInstance();
        if (mService != null) try {
            mService.reloadKeymap();
        } catch (RemoteException e) {
            Log.i("RemoteService", e.toString());
        }
    }
}
