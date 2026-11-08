package android.app;

import android.os.Binder;

public interface IProcessObserver {
    void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws android.os.RemoteException;
    void onForegroundServicesChanged(int pid, int uid, int serviceTypes) throws android.os.RemoteException;
    void onProcessDied(int pid, int uid) throws android.os.RemoteException;

    abstract class Stub extends Binder implements IProcessObserver {
    }
}