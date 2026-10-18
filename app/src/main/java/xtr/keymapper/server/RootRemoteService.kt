package xtr.keymapper.server

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService

internal class RootRemoteService : RootService() {
    init {
        // Only load the library when this class is loaded in a root process.
        // The classloader will load this class (and call this static block) in the non-root
        // process because we accessed it when constructing the Intent to send.
        // Add this check so we don't unnecessarily load native code that'll never be used.
        if (Process.myUid() == 0)
            RemoteService.loadLibraries()
    }

    private var mService: RemoteService? = null

    override fun onBind(intent: Intent): IBinder {
        if (mService == null) {
            mService = RemoteService(this)
        }
        return mService!!
    }
}
