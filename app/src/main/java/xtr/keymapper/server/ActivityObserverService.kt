package xtr.keymapper.server

import android.app.ActivityManager
import android.app.IActivityManager
import android.content.Context.ACTIVITY_SERVICE
import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import xtr.keymapper.ActivityObserver

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
            val taskInfo = am.getTasks(1)
            if (taskInfo != null && taskInfo.isNotEmpty() && taskInfo[0].topActivity != null) {
                val packageName = taskInfo[0].topActivity!!.packageName
                val callback = mCallback
                if (callback != null) {
                    callback.onForegroundActivitiesChanged(packageName)
                    mHandler?.postDelayed(this, 1000)
                } else {
                    stop()
                }
            } else {
                mHandler?.postDelayed(this, 1000)
            }
        } catch (e: RemoteException) {
            Log.e(RemoteService.TAG, e.message, e)
            mHandler?.postDelayed(this, 1000)
        }
    }

    fun stop() {
        mCallback = null
        mHandler = null
        mHandlerThread?.quit()
        mHandlerThread = null
    }
}
