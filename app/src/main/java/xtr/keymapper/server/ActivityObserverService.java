package xtr.keymapper.server;

import static android.content.Context.ACTIVITY_SERVICE;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.List;

import xtr.keymapper.ActivityObserver;

public class ActivityObserverService implements Runnable {
    public ActivityObserver mCallback;
    private final IActivityManager am = IActivityManager.Stub.asInterface(ServiceManager.getService(ACTIVITY_SERVICE));
    private String currentActivity = null;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    public ActivityObserverService(ActivityObserver observer) {
        this.mCallback = observer;

        mHandlerThread = new HandlerThread("activity_observer");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        // Send activity to client app every 5seconds
        mHandler.postDelayed(this, 5000);
    }

    @Override
    public void run() {
        try {
            List<ActivityManager.RunningTaskInfo> taskInfo = am.getTasks(1);
            String packageName = taskInfo.get(0).topActivity.getPackageName();
            if (mCallback != null) {
                if (!packageName.equals(currentActivity)) {
                    currentActivity = packageName;
                    mCallback.onForegroundActivitiesChanged(packageName);
                }
                mHandler.postDelayed(this, 5000);
            } else {
                stop();
            }
        } catch (RemoteException e) {
            e.printStackTrace(System.out);
        }
    }

    public void stop() {
        mCallback = null;
        mHandler = null;
        if (mHandlerThread != null)
            mHandlerThread.quit();
        mHandlerThread = null;
    }
}
