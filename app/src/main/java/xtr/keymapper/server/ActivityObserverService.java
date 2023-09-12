package xtr.keymapper.server;

import static android.content.Context.ACTIVITY_SERVICE;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.List;

import xtr.keymapper.ActivityObserver;

public class ActivityObserverService extends IProcessObserver.Stub {
    public ActivityObserver mCallback;

    public ActivityObserverService() {
        IActivityManager am = IActivityManager.Stub.asInterface(ServiceManager.getService(ACTIVITY_SERVICE));
        try {
            am.registerProcessObserver(this);
        } catch (RemoteException e) {
            e.printStackTrace(System.out);
        }
    }

    @Override
    public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
        if (!fg) return;
        IActivityManager am = IActivityManager.Stub.asInterface(ServiceManager.getService(ACTIVITY_SERVICE));
        try {
            List<ActivityManager.RunningTaskInfo> taskInfo = am.getTasks(1);
            if ( mCallback != null )
                mCallback.onForegroundActivitiesChanged(taskInfo.get(0).topActivity.getPackageName());
        } catch (RemoteException e) {
            e.printStackTrace(System.out);
        }
    }

    @Override
    public void onProcessDied(int pid, int uid) {
    }

    @Override
    public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) {
    }
}
