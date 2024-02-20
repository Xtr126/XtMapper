package xtr.keymapper.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.topjohnwu.superuser.ipc.RootService;

import java.io.IOException;

import xtr.keymapper.IRemoteService;

public class RemoteServiceHelper {

    private static IRemoteService service = null;

    public static void pauseKeymap(Context context){
        RemoteServiceHelper.getInstance(context, service -> {
            try {
                service.pauseMouse();
            } catch (RemoteException e) {
                Log.i("RemoteService", e.toString());
            }
        });
    }

    public static void resumeKeymap(Context context){
        RemoteServiceHelper.getInstance(context, service -> {
            try {
                service.resumeMouse();
            } catch (RemoteException e) {
                Log.i("RemoteService", e.toString());
            }
        });
    }

    public static void reloadKeymap(Context context) {
        RemoteServiceHelper.getInstance(context, service -> {
            try {
                service.reloadKeymap();
            } catch (RemoteException e) {
                Log.i("RemoteService", e.toString());
            }
        });
    }

    public interface RootRemoteServiceCallback {
        void onConnection(IRemoteService service);
    }
    public static class RemoteServiceConnection implements ServiceConnection {
        RootRemoteServiceCallback cb;
        public RemoteServiceConnection(RootRemoteServiceCallback cb){
            this.cb = cb;
        }
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            cb.onConnection(IRemoteService.Stub.asInterface(service));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    }

    public static void getInstance(){
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
    }

    public static void getInstance(Context context, RootRemoteServiceCallback cb){
        getInstance();
        if (service != null) cb.onConnection(service);
        else {
            RemoteServiceConnection connection = new RemoteServiceConnection(cb);
            Intent intent = new Intent(context, RootRemoteService.class);
            RootService.bind(intent, connection);
        }
    }
}
