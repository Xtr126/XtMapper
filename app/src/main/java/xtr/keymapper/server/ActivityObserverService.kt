package xtr.keymapper.server

import android.app.ActivityManager
import android.app.IActivityManager
import android.content.Context.ACTIVITY_SERVICE
import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import android.os.ServiceManager
import xtr.keymapper.ActivityObserver
import xtr.keymapper.log.XtLogger

class ActivityObserverService(observer: ActivityObserver) : Runnable {
    var mCallback: ActivityObserver? = observer
    private val am: IActivityManager = IActivityManager.Stub.asInterface(ServiceManager.getService(ACTIVITY_SERVICE))
    private var mHandlerThread: HandlerThread? = null
    private var mHandler: Handler? = null

    init {
        mHandlerThread = HandlerThread("activity_observer").also {
            it.start()
            mHandler = Handler(it.looper)
        }
        // Send activity to client app every 5 seconds
        mHandler!!.post(this)
    }

    override fun run() {
        try {
            val taskInfo = am.getTasks(5)
            var targetPackage: String? = null
            if (taskInfo != null && taskInfo.isNotEmpty()) {
                for (task in taskInfo) {
                    val pkg = task.topActivity?.packageName ?: continue
                    if (pkg != "com.android.systemui" && !pkg.startsWith("xtr.keymapper")) {
                        targetPackage = pkg
                        break
                    }
                }
                if (targetPackage == null && taskInfo[0].topActivity != null) {
                    targetPackage = taskInfo[0].topActivity!!.packageName
                }
            }
            if (targetPackage != null) {
                val callback = mCallback
                if (callback != null) {
                    callback.onForegroundActivitiesChanged(targetPackage)
                    mHandler?.postDelayed(this, 5000)
                } else {
                    stop()
                }
            } else {
                mHandler?.postDelayed(this, 5000)
            }
        } catch (e: RemoteException) {
            XtLogger.error(RemoteService.TAG, "Failed to query foreground activities; retrying", e)
            mHandler?.postDelayed(this, 5000)
        }
    }

    fun stop() {
        mCallback = null
        mHandler = null
        mHandlerThread?.quit()
        mHandlerThread = null
    }
}
