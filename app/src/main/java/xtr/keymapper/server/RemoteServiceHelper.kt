package xtr.keymapper.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.Shizuku
import xtr.keymapper.BuildConfig
import xtr.keymapper.IRemoteService
import xtr.keymapper.TouchPointer
import xtr.keymapper.keymap.KeymapConfig

object RemoteServiceHelper {

    @Volatile
    private var service: IRemoteService? = null

    /** Forces the next connection to honor the selected Root/Shizuku backend. */
    @Volatile
    private var forceSelectedBackend = false

    /** Invalidates callbacks from binds started before an input-mode switch. */
    @Volatile
    private var backendGeneration = 0L

    @JvmStatic
    @Volatile
    var useShizuku = false

    @JvmStatic
    fun pauseKeymap(context: Context) {
        getInstance(context) { service ->
            try {
                service?.pauseMouse()
            } catch (e: RemoteException) {
                Log.i(RemoteService.TAG, e.message, e)
            }
        }
    }

    @JvmStatic
    fun resumeKeymap(context: Context) {
        getInstance(context) { service ->
            try {
                service?.resumeMouse()
            } catch (e: RemoteException) {
                Log.i(RemoteService.TAG, e.message, e)
            }
        }
    }

    @JvmStatic
    fun reloadKeymap(context: Context) {
        getInstance(context) { service ->
            try {
                service?.reloadKeymap()
            } catch (e: RemoteException) {
                Log.i(RemoteService.TAG, e.message, e)
            }
        }
    }

    @JvmStatic
    fun runIfActive(context: Context, runnable: Runnable) {
        getInstance(context) { service ->
            try {
                if (service != null && service.isActive) runnable.run()
            } catch (_: RemoteException) {
            }
        }
    }

    fun interface RootRemoteServiceCallback {
        fun onConnection(service: IRemoteService?)
    }

    /** Invalidate cached and pending Binders before restarting TouchPointer for a new backend. */
    @JvmStatic
    fun prepareForBackendSwitch() {
        backendGeneration++
        service = null
        forceSelectedBackend = true
    }

    class RemoteServiceConnection(
        private val cb: RootRemoteServiceCallback,
        private val requestGeneration: Long,
        private val requestedShizuku: Boolean,
    ) : ServiceConnection {
        @Volatile
        private var connectedBinder: IBinder? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i("xtmapper-startup", "onServiceConnected: component=$name alive=${service.isBinderAlive}")
            // A bind started before a mode switch must not replace the current backend.
            if (requestGeneration != RemoteServiceHelper.backendGeneration ||
                requestedShizuku != RemoteServiceHelper.useShizuku
            ) {
                // This bind belongs to a superseded request; don't let its callback
                // disrupt the new TouchPointer instance while it connects to the new mode.
                return
            }

            connectedBinder = service
            val remote = IRemoteService.Stub.asInterface(service)
            RemoteServiceHelper.service = remote
            RemoteServiceHelper.forceSelectedBackend = false
            try {
                service.linkToDeath({
                    if (RemoteServiceHelper.service?.asBinder() == service) {
                        RemoteServiceHelper.service = null
                    }
                }, 0)
            } catch (_: RemoteException) {}
            cb.onConnection(remote)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // A disconnect from a superseded connection must not erase the newer
            // backend's Binder or notify a callback belonging to the old generation.
            if (requestGeneration != RemoteServiceHelper.backendGeneration) return
            if (RemoteServiceHelper.service?.asBinder() == connectedBinder) {
                RemoteServiceHelper.service = null
                cb.onConnection(null)
            }
        }
    }

    @JvmStatic
    fun setShellServiceBinder(binder: IBinder?) {
        val remote = binder?.let { IRemoteService.Stub.asInterface(it) }
        service = remote
        Log.i("xtmapper-startup", "setShellServiceBinder: present=${binder != null} alive=${binder?.isBinderAlive}")
        try {
            val remoteBinder = remote?.asBinder()
            remoteBinder?.linkToDeath({
                if (service?.asBinder() == remoteBinder) service = null
            }, 0)
        } catch (_: RemoteException) {
        }
    }

    private fun bindShizukuService(context: Context, connection: RemoteServiceConnection) {
        val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, RemoteService::class.java.name))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        Shizuku.bindUserService(userServiceArgs, connection)
    }

    @JvmStatic
    fun isSystemApp(context: Context): Boolean {
        return try {
            val packageName = context.packageName
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstanceAsSystemApp(context: Context, callback: RootRemoteServiceCallback?) {
        if (service == null) {
            val mService = RemoteService(context.applicationContext)
            mService.startedFromShell = true
            service = mService
        }
        // The system-app path intentionally uses its in-process service rather than
        // RootService/Shizuku, so backend selection is not applicable here.
        forceSelectedBackend = false
        callback?.onConnection(service)
    }

    /**
     * Get instance of remote service
     * @param context App context
     * @param callback Callback when instance of remote service is obtained
     */
    @JvmStatic
    fun getInstance(context: Context, callback: RootRemoteServiceCallback) {
        useShizuku = KeymapConfig(context).isShizukuMode
        Log.i("xtmapper-startup", "getInstance: shizuku=$useShizuku cachedPresent=${service != null} cachedAlive=${service?.asBinder()?.isBinderAlive}")

        val forceBackend = forceSelectedBackend

        // 1. Reuse the active TouchPointer Binder only when the caller has not
        // explicitly requested a backend switch.
        val activePointerService = TouchPointer.activeInstance?.mService
        if (!forceBackend && activePointerService != null && activePointerService.asBinder().isBinderAlive) {
            service = activePointerService
            Log.i("xtmapper-startup", "getInstance: using active TouchPointer service")
            callback.onConnection(activePointerService)
            return
        }

        // 2. Check if we are a system app
        if (isSystemApp(context)) {
            getInstanceAsSystemApp(context, callback)
            return
        }

        // A mode switch must bypass an old live Binder while stopServer() is still completing.
        if (!forceBackend) {
            val existing = service
            if (existing != null && existing.asBinder().isBinderAlive) {
                Log.i("xtmapper-startup", "getInstance: using cached service Binder")
                callback.onConnection(existing)
                return
            }
        }

        Log.i("xtmapper-startup", "getInstance: falling back to ${if (useShizuku) "Shizuku" else "root"} binding")
        val connection = RemoteServiceConnection(callback, backendGeneration, useShizuku)
        if (useShizuku) {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShizukuService(context, connection)
            } else {
                callback.onConnection(null)
            }
        } else {
            if (Shell.isAppGrantedRoot() != false) {
                val intent = Intent(context, RootRemoteService::class.java)
                RootService.bind(intent, connection)
            } else {
                callback.onConnection(null)
            }
        }
    }
}
