package xtr.keymapper.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.Shizuku
import xtr.keymapper.BuildConfig
import xtr.keymapper.IRemoteService
import xtr.keymapper.IShellAuthenticationCallback
import xtr.keymapper.TouchPointer
import xtr.keymapper.keymap.KeymapConfig
import java.security.SecureRandom

object RemoteServiceHelper {

    @Volatile
    private var service: IRemoteService? = null

    @Volatile
    private var forceSelectedBackend = false

    @Volatile
    private var backendGeneration = 0L

    private val backendLock = Any()

    @Volatile
    private var shellServiceBinder: IBinder? = null

    @Volatile
    private var shellServiceAuthenticationPending = false

    private var shellAuthenticationRequestId = 0L

    private const val SHELL_AUTHENTICATION_TIMEOUT_MILLIS = 2_000L

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

    @JvmStatic
    fun prepareForBackendSwitch() {
        synchronized(backendLock) {
            backendGeneration++
            shellAuthenticationRequestId++
            shellServiceAuthenticationPending = false
            shellServiceBinder = null
            service = null
            forceSelectedBackend = true
        }
    }

    class RemoteServiceConnection(
        private val cb: RootRemoteServiceCallback,
        private val requestGeneration: Long,
        private val requestedShizuku: Boolean,
    ) : ServiceConnection {
        @Volatile
        private var connectedBinder: IBinder? = null

        private fun releaseStaleRootBinding() {
            if (requestedShizuku) return
            runCatching { RootService.unbind(this) }
                .onFailure { Log.w("xtmapper-startup", "Failed to unbind a stale root service connection", it) }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i("xtmapper-startup", "onServiceConnected: component=$name alive=${service.isBinderAlive}")
            if (requestGeneration != RemoteServiceHelper.backendGeneration ||
                requestedShizuku != RemoteServiceHelper.useShizuku
            ) {
                releaseStaleRootBinding()
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
            if (requestGeneration != RemoteServiceHelper.backendGeneration ||
                requestedShizuku != RemoteServiceHelper.useShizuku
            ) {
                releaseStaleRootBinding()
                return
            }
            if (RemoteServiceHelper.service?.asBinder() == connectedBinder) {
                RemoteServiceHelper.service = null
                cb.onConnection(null)
            }
        }
    }

    @JvmStatic
    fun hasLiveShellService(): Boolean {
        if (forceSelectedBackend) return false
        val shellBinder = shellServiceBinder ?: return false
        return shellBinder.isBinderAlive && service?.asBinder() == shellBinder
    }

    @JvmStatic
    fun isShellServiceAuthenticationPending(): Boolean = shellServiceAuthenticationPending

    /** Cache a shell Binder only after Android has verified the launching UID. */
    @JvmStatic
    fun setAuthenticatedShellServiceBinder(binder: IBinder?) {
        synchronized(backendLock) {
            shellAuthenticationRequestId++
            shellServiceAuthenticationPending = false
            cacheShellServiceBinderLocked(binder)
        }
    }

    /**
     * Authenticate a shell Binder on Android versions that do not expose the activity's
     * launching UID. The callback's Binder caller UID is supplied by the kernel.
     */
    @JvmStatic
    fun authenticateShellServiceBinder(binder: IBinder?) {
        if (binder == null || !binder.isBinderAlive) return

        val (requestId, expectedBackendGeneration) = synchronized(backendLock) {
            if (forceSelectedBackend) return
            shellAuthenticationRequestId++
            shellServiceAuthenticationPending = true
            shellAuthenticationRequestId to backendGeneration
        }

        val challenge = SecureRandom().nextLong()
        val authenticator = ShellServiceAuthenticator(challenge, Process.SHELL_UID) {
            synchronized(backendLock) {
                if (shellAuthenticationRequestId != requestId ||
                    backendGeneration != expectedBackendGeneration ||
                    forceSelectedBackend ||
                    !shellServiceAuthenticationPending ||
                    !binder.isBinderAlive
                ) return@synchronized

                cacheShellServiceBinderLocked(binder)
                shellServiceAuthenticationPending = false
            }
        }
        val callback = object : IShellAuthenticationCallback.Stub() {
            override fun onShellServiceAuthenticated(challengeResponse: Long) {
                authenticator.authenticate(Binder.getCallingUid(), challengeResponse)
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            synchronized(backendLock) {
                if (shellAuthenticationRequestId == requestId) {
                    shellServiceAuthenticationPending = false
                }
            }
        }, SHELL_AUTHENTICATION_TIMEOUT_MILLIS)

        Thread({
            try {
                IRemoteService.Stub.asInterface(binder)
                    ?.authenticateShellClient(callback, challenge)
            } catch (e: Exception) {
                Log.w("xtmapper-startup", "Shell service Binder authentication failed", e)
            } finally {
                if (!authenticator.isAuthenticated) {
                    synchronized(backendLock) {
                        if (shellAuthenticationRequestId == requestId) {
                            shellServiceAuthenticationPending = false
                        }
                    }
                }
            }
        }, "xtmapper-shell-binder-auth").apply {
            isDaemon = true
            start()
        }
    }

    private fun cacheShellServiceBinderLocked(binder: IBinder?) {
        if (binder == null || !binder.isBinderAlive) return
        val remote = IRemoteService.Stub.asInterface(binder) ?: return
        val remoteBinder = remote.asBinder()
        shellServiceBinder = remoteBinder
        service = remote
        try {
            remoteBinder.linkToDeath({
                synchronized(backendLock) {
                    if (shellServiceBinder == remoteBinder) shellServiceBinder = null
                    if (service?.asBinder() == remoteBinder) service = null
                }
            }, 0)
        } catch (_: RemoteException) {
            if (shellServiceBinder == remoteBinder) shellServiceBinder = null
            if (service?.asBinder() == remoteBinder) service = null
        }
        Log.i("xtmapper-startup", "authenticated shell Binder cached: alive=${remoteBinder.isBinderAlive}")
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
        forceSelectedBackend = false
        callback?.onConnection(service)
    }

    @JvmStatic
    fun getInstance(context: Context, callback: RootRemoteServiceCallback) {
        useShizuku = KeymapConfig(context).isShizukuMode
        Log.i("xtmapper-startup", "getInstance: shizuku=$useShizuku cachedPresent=${service != null} cachedAlive=${service?.asBinder()?.isBinderAlive}")

        val forceBackend = forceSelectedBackend

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
