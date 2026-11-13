package xtr.keymapper.server

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import xtr.keymapper.log.Role
import xtr.keymapper.log.XtLogger
import xtr.keymapper.log.SessionStates

internal class RootRemoteService : RootService() {
    init {
        if (Process.myUid() == 0) {
            XtLogger.initStandalone(Role.ROOT)
            try {
                RemoteService.loadLibraries()
            } catch (t: Throwable) {
                XtLogger.fatal(TAG, "native library load failed", t)
                throw t
            }
        }
    }

    private var mService: RemoteService? = null

    override fun onBind(intent: Intent): IBinder {
        XtLogger.info(TAG, "root server bind requested")
        if (mService == null) {
            mService = RemoteService(this)
        }
        return mService!!
    }

    override fun onUnbind(intent: Intent): Boolean {
        XtLogger.info(TAG, "root server unbound")
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "RootRemoteService"
    }
}
