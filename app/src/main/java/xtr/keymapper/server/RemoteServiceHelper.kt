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

object RemoteServiceHelper {

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
            } catch (e: RemoteException) {
            }
        }
    }

    fun interface RootRemoteServiceCallback {
        fun onConnection(service: IRemoteService?)
    }

    class RemoteServiceConnection(private val cb: RootRemoteServiceCallback) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            cb.onConnection(IRemoteService.Stub.asInterface(service))
        }

        override fun onServiceDisconnected(name: ComponentName) {
        }
    }

    private fun getInstance() {
        if (service == null) {
            service = IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"))
            try {
                service?.asBinder()?.linkToDeath({ service = null }, 0)
            } catch (ignored: RemoteException) {
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
        } catch (e: PackageManager.NameNotFoundException) {
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
     * Check if we are a system app
     * If system app -> create instance of service in same process
     * If not -> Try to get instance from xtmapper binder service added with ServiceManager
     * Or if Shizuku is available try to obtain service instance
     * Or if root is available try to start server and obtain instance
     */
    @JvmStatic
    fun getInstance(context: Context, callback: RootRemoteServiceCallback) {
        if (!useShizuku && xtr.keymapper.keymap.KeymapConfig(context).isShizukuMode) {
            useShizuku = true
        }
        if (isSystemApp(context)) {
            getInstanceAsSystemApp(context, callback)
        } else {
            getInstance()
            val s = service
            if (s != null) {
                callback.onConnection(s)
            } else {
                val connection = RemoteServiceConnection(callback)
                if (useShizuku) {
                    if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                        bindShizukuService(context, connection)
                    else callback.onConnection(null)
                } else {
                    if (Shell.isAppGrantedRoot() == true) {
                        val intent = Intent(context, RootRemoteService::class.java)
                        RootService.bind(intent, connection)
                    } else callback.onConnection(null)
                }
            }
        }
    }
}
