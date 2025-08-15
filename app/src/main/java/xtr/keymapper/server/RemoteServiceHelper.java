package xtr.keymapper.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import rikka.shizuku.Shizuku;
import xtr.keymapper.BuildConfig;
import xtr.keymapper.IRemoteService;

public class RemoteServiceHelper {

    private static IRemoteService service = null;
    public static boolean useShizuku = false;

    public static void pauseKeymap(Context context){
        getInstance(context, service -> {
            try {
                service.pauseMouse();
            } catch (RemoteException e) {
                Log.i(RemoteService.TAG, e.getMessage(), e);
            }
        });
    }

    public static void resumeKeymap(Context context){
        RemoteServiceHelper.getInstance(context, service -> {
            try {
                service.resumeMouse();
            } catch (RemoteException e) {
                Log.i(RemoteService.TAG, e.getMessage(), e);
            }
        });
    }

    public static void reloadKeymap(Context context) {
        RemoteServiceHelper.getInstance(context, service -> {
            if (service != null) try {
                service.reloadKeymap();
            } catch (RemoteException e) {
                Log.i(RemoteService.TAG, e.getMessage(), e);
            }
        });
    }

    public static void runIfActive(Context context, Runnable runnable) {
        getInstance(context, service -> {
            try {
                if (service != null && service.isActive()) runnable.run();
            } catch (RemoteException ignored) {
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

    private static void getInstance(){
        if (service == null) {
                service = IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"));
                if (service != null) try {
                    service.asBinder().linkToDeath(() -> service = null, 0);
                } catch (RemoteException ignored) {
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

    public static boolean isSystemApp(Context context) {
        try {
            String packageName = context.getPackageName();
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    
    private static void getInstanceAsSystemApp(Context context, RootRemoteServiceCallback callback) {
        if (service == null) {
            RemoteService mService = new RemoteService(context.getApplicationContext());
            mService.startedFromShell = true;
            service = mService;
        }
        if (callback != null) callback.onConnection(service);
    }

    /**
     * Get instance of remote service
     * @param context App context
     * @param callback Callback when instance of remote service is obtained
     * Check if we are a system app
     * If system app -> create instance of service in same process
     * If not -> Try to get instance from xtmapper binder service added with ServiceManager
     * Or if Shizuku is available try to obtain service instance
     * Or if root is available try to start server and obtain instance 
     */
    public static void getInstance(Context context, RootRemoteServiceCallback callback) {
        if (isSystemApp(context)) {
            getInstanceAsSystemApp(context, callback);
        } else {
            getInstance();
            if (service != null) {
                callback.onConnection(service);
            } else {
                RemoteServiceConnection connection = new RemoteServiceConnection(callback);
                if (useShizuku) {
                    if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                        bindShizukuService(context, connection);
                    else callback.onConnection(null);
                } else {
                    if (Boolean.TRUE.equals(Shell.isAppGrantedRoot())) {
                        Intent intent = new Intent(context, RootRemoteService.class);
                        RootService.bind(intent, connection);
                    } else callback.onConnection(null);
                }
            }
        }
    }
}
