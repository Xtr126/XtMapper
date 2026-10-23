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
        // Only load the library when this class is loaded in a root process.
        // The classloader will load this class (and call this static block) in the non-root
        // process because we accessed it when constructing the Intent to send.
        // Add this check so we don't unnecessarily load native code that'll never be used.
        if (Process.myUid() == 0) {
            // Logging must be available before the native library load so a load
            // failure is recorded in the canonical user-visible log directory.
            XtLogger.initStandalone(Role.ROOT)
            RemoteService.loadLibraries()
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
