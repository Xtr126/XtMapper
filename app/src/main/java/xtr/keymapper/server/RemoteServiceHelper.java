package xtr.keymapper.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import java.io.IOException;

import rikka.shizuku.Shizuku;
import xtr.keymapper.BuildConfig;
import xtr.keymapper.IRemoteService;

public class RemoteServiceHelper {

    private static IRemoteService service = null;
    public static boolean isRootService = true;
    public static boolean useShizuku = false;

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
                Log.e("RemoteService", e.getMessage(), e);
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
                // Log.e(e.toString(), e.getMessage(), e);
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

    private static void bindShizukuService(Context context, RemoteServiceConnection connection) {
        Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName(context, RemoteService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("service")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);
        Shizuku.bindUserService(userServiceArgs, connection);
}

    public static void getInstance(Context context, RootRemoteServiceCallback callback){
        getInstance();
        if (service != null) callback.onConnection(service);
        else {
            RemoteServiceConnection connection = new RemoteServiceConnection(callback);
            if (useShizuku) {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                    bindShizukuService(context, connection);
            } else {
                Boolean hasRootAccess = Shell.isAppGrantedRoot();
                if (hasRootAccess != null) isRootService = hasRootAccess;
                Intent intent = new Intent(context, RootRemoteService.class);
                RootService.bind(intent, connection);
            }
        }
    }
}
