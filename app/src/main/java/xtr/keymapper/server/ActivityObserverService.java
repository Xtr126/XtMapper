package xtr.keymapper.server;

import static android.content.Context.ACTIVITY_SERVICE;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.List;

import xtr.keymapper.ActivityObserver;

public class ActivityObserverService implements Runnable {
    private final Handler mHandler;
    public ActivityObserver mCallback;
    private final IActivityManager am = IActivityManager.Stub.asInterface(ServiceManager.getService(ACTIVITY_SERVICE));
    private String currentActivity = null;

    public ActivityObserverService(ActivityObserver observer) {
        this.mCallback = observer;
        mHandler = new Handler(Looper.myLooper());
        // Send activity to client app every 5seconds
        mHandler.postDelayed(this, 5000);
    }

    @Override
    public void run() {
        try {
            List<ActivityManager.RunningTaskInfo> taskInfo = am.getTasks(1);
            String packageName = taskInfo.get(0).topActivity.getPackageName();
            if ( mCallback != null && !packageName.equals(currentActivity) ) {
                currentActivity = packageName;
                mCallback.onForegroundActivitiesChanged(packageName);
            }
            if (mCallback != null) mHandler.postDelayed(this, 5000);
        } catch (RemoteException e) {
            e.printStackTrace(System.out);
        }
    }

}
