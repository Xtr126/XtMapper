package xtr.keymapper.server

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemProperties
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import xtr.keymapper.BuildConfig
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.log.Role
import xtr.keymapper.log.XtLogger
import java.lang.reflect.Method
import kotlin.system.exitProcess

object RemoteServiceShell {

    private const val TAG = "xtmapper-server"

    @JvmStatic
    fun main(args: Array<String>) {
        // First statement: logging is available before anything else so even
        // startup failures below can be recorded (uid/role based storage).
        XtLogger.initStandalone(Role.SHELL)
        XtLogger.debug(TAG, "app_process server starting (args=${args.joinToString(",")})")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            println("Unsupported Android version")
            XtLogger.endSession("Unsupported Android version")
            return
        }

        try {
            Looper.prepareMainLooper()
            var noLogcat = false
            var isWaylandClient = false

            var autoLaunchClientActivity = true
            for (arg in args) {
                when (arg) {
                    "--wayland-client" -> {
                        isWaylandClient = true
                        println("using wayland client")
                    }
                    "--no-auto-launch" -> autoLaunchClientActivity = false
                    "--no-logcat" -> noLogcat = true
                    else -> println("Invalid argument: $arg")
                }
            }
            // Session close best effort: runs when the JVM exits normally and when
            // stopServer ends the process via System.exit. SIGKILL/SIGTERM kill the
            // process without running hooks; the next session reports that as
            // "did not close cleanly".
            Runtime.getRuntime().addShutdownHook(
                Thread { XtLogger.endSession("Shell server exiting") }
            )
            val fakeTouch = SystemProperties.get("persist.waydroid.fake_touch", null)
            if (fakeTouch != null && !fakeTouch.isBlank()) {
                val ANSI_RED = "\u001B[31m"
                val ANSI_RESET = "\u001B[0m"
                println(ANSI_RED + "Warning: persist.waydroid.fake_touch=" + fakeTouch + " set" + ANSI_RESET)
                println(ANSI_RED + "Reset it with " + "waydroid prop set persist.waydroid.fake_touch \"\" to avoid issues with XtMapper" + ANSI_RESET)
            } else if (!noLogcat) {
                // Don't flood the console with logcat if warning is being shown
                ProcessBuilder("logcat", "-v", "color", "--pid=" + Process.myPid()).inheritIO().start()
            }

            // Do everything after logcat
            try {
                RemoteService.loadLibraries()
            } catch (t: Throwable) {
                // Native load failure kills the process in existing behavior;
                // rethrowing preserves that while the failure is now logged.
                XtLogger.fatal(TAG, "native library load failed", t)
                throw t
            }
            XtLogger.info(TAG, "native libraries loaded")

            val mService = RemoteService(getContext())
            mService.startedFromShell = true
            mService.isWaylandClient = isWaylandClient

            XtLogger.info(TAG, "server configuration ready wayland=${mService.isWaylandClient}")

            ProcessBuilder("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.SYSTEM_ALERT_WINDOW").inheritIO().start()
            val settingsArgs = "settings put system alert_window_bypass_low_ram 1".split(Regex("\\s+"))
            ProcessBuilder(settingsArgs).inheritIO().start()

            Log.i(RemoteService.TAG, "shell startup: uid=${Process.myUid()} package=${BuildConfig.APPLICATION_ID} autoLaunch=$autoLaunchClientActivity wayland=$isWaylandClient")
            if (autoLaunchClientActivity) {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(BuildConfig.APPLICATION_ID, MainActivity::class.java.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("data", MainActivity.SHELL_INIT)
                    putExtras(Bundle().apply {
                        putBinder(MainActivity.SHELL_SERVICE_BINDER, mService.asBinder())
                    })
                }
                Log.i(RemoteService.TAG, "shell startup: delivering service Binder to MainActivity")
                startActivityAsShell(intent)
                Log.i(RemoteService.TAG, "shell startup: MainActivity launch returned")
            }

        } catch (e: Exception) {
            Log.e(RemoteService.TAG, e.message, e)
            XtLogger.error(TAG, "server startup failed", e)
            // Bounded flush before the existing exit(1) behavior.
            XtLogger.flush(2_000)
            XtLogger.endSession("Startup failed")
            exitProcess(1)
        }
        Looper.loop()
    }

    /** Launch with the shell package identity and carry Binder extras in the Intent. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun startActivityAsShell(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(RemoteService.TAG, "Launching MainActivity with a binder requires Android 9 or newer")
            exitProcess(1)
        }
        HiddenApiBypass.addHiddenApiExemptions("")
        val activityManager = ActivityManager::class.java.getMethod("getService").invoke(null)
        val currentUserId = runCatching {
            ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        }.getOrElse { Process.myUid() / 100000 }
        val expectedTypes = listOf(
            "android.app.IApplicationThread",
            String::class.java.name,
            Intent::class.java.name,
            String::class.java.name,
            IBinder::class.java.name,
            String::class.java.name,
            Int::class.javaPrimitiveType!!.name,
            Int::class.javaPrimitiveType!!.name,
            Bundle::class.java.name,
            Int::class.javaPrimitiveType!!.name
        )
        val values = listOf(
            null,
            "com.android.shell",
            intent,
            intent.type,
            null,
            null,
            -1,
            0,
            null,
            currentUserId
        )

        val method = activityManager.javaClass.methods.firstOrNull { candidate ->
            if (candidate.name != "startActivityAsUser") return@firstOrNull false
            var expectedIndex = 0
            for (parameterType in candidate.parameterTypes) {
                if (expectedIndex < expectedTypes.size && parameterType.name == expectedTypes[expectedIndex]) {
                    expectedIndex++
                }
            }
            expectedIndex == expectedTypes.size
        } ?: error("ActivityManager.startActivityAsUser method not found")

        val args = arrayOfNulls<Any>(method.parameterCount)
        var expectedIndex = 0
        for ((index, parameterType) in method.parameterTypes.withIndex()) {
            if (expectedIndex < expectedTypes.size && parameterType.name == expectedTypes[expectedIndex]) {
                args[index] = values[expectedIndex++]
            } else if (parameterType.isPrimitive) {
                args[index] = when (parameterType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    Character.TYPE -> '\u0000'
                    java.lang.Byte.TYPE -> 0.toByte()
                    java.lang.Short.TYPE -> 0.toShort()
                    else -> 0
                }
            }
        }

        val result = method.invoke(activityManager, *args) as? Int
        Log.i(RemoteService.TAG, "shell startup: startActivityAsUser result=$result user=$currentUserId")
        check(result == null || result >= 0) { "ActivityManager failed to start MainActivity: $result" }
    }

    @JvmStatic
    fun getContextImpl(context: Context): Context {
        var context = context
        while (context is ContextWrapper) {
            context = context.baseContext
        }
        return context
    }

    @JvmStatic
    fun getContext(): Context {
        val systemContext = getSystemContext()
        var context: Context? = null
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        try {
            context = systemContext.createPackageContext(BuildConfig.APPLICATION_ID, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(RemoteService.TAG, e.message, e)
        }
        return getContextImpl(context!!)
    }

    @SuppressLint("PrivateApi")
    internal fun getSystemContext(): Context {
        try {
            val atClazz = Class.forName("android.app.ActivityThread")
            val systemMain: Method = atClazz.getMethod("systemMain")
            val activityThread = systemMain.invoke(null)
            val getSystemContext: Method = atClazz.getMethod("getSystemContext")
            return getSystemContext.invoke(activityThread) as Context
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
