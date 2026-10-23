package xtr.keymapper.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
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

    @JvmStatic
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

    class RemoteServiceConnection(private val cb: RootRemoteServiceCallback) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val remote = IRemoteService.Stub.asInterface(service)
            RemoteServiceHelper.service = remote
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
            RemoteServiceHelper.service = null
            cb.onConnection(null)
        }
    }

    private fun getInstance() {
        if (service == null) {
            service = IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"))
            try {
                service?.asBinder()?.linkToDeath({ service = null }, 0)
            } catch (_: RemoteException) {
            }
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

        // 1. Check if TouchPointer has an active, running service instance
        val activePointerService = TouchPointer.activeInstance?.mService
        if (activePointerService != null && activePointerService.asBinder().isBinderAlive) {
            service = activePointerService
            callback.onConnection(activePointerService)
            return
        }

        // 2. Check if we are a system app
        if (isSystemApp(context)) {
            getInstanceAsSystemApp(context, callback)
            return
        }

        // 3. Check existing live cached service
        val existing = service
        if (existing != null && existing.asBinder().isBinderAlive) {
            callback.onConnection(existing)
            return
        }

        // 4. Try ServiceManager (shell started service)
        getInstance()
        val smService = service
        if (smService != null && smService.asBinder().isBinderAlive) {
            callback.onConnection(smService)
            return
        }

        // 5. Connect via Shizuku or Root
        val connection = RemoteServiceConnection(callback)
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
