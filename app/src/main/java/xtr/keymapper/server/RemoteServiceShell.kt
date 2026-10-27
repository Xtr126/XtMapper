package xtr.keymapper.server

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemProperties
import android.util.Log
import xtr.keymapper.BuildConfig
import xtr.keymapper.activity.MainActivity
import java.lang.reflect.Method

object RemoteServiceShell {
    @JvmStatic
    fun main(args: Array<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            println("Unsupported Android version")
            return
        }

        try {
            RemoteService.loadLibraries()
            Looper.prepareMainLooper()
            val mService = RemoteService(getContext())
            mService.startedFromShell = true
            var noLogcat = false

            var autoLaunchClientActivity = true
            for (arg in args) {
                when (arg) {
                    "--wayland-client" -> {
                        mService.isWaylandClient = true
                        println("using wayland client")
                    }
                    "--no-auto-launch" -> autoLaunchClientActivity = false
                    "--no-logcat" -> noLogcat = true
                    else -> println("Invalid argument: $arg")
                }
            }
            Selinux.real.relax()
            // Hooks run only on System.exit (crash handler, stopServer exit) — Android does NOT
            // run them when the process is killed with SIGTERM (e.g. pkill); a clean stop is
            // restored by RemoteService.stopServer instead
            Runtime.getRuntime().addShutdownHook(
                Thread { ProcessBuilder("setenforce", "1").start() }
            )
            ServiceManager.addService("xtmapper", mService)

            ProcessBuilder("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.SYSTEM_ALERT_WINDOW").inheritIO().start()
            val settingsArgs = "settings put system alert_window_bypass_low_ram 1".split(Regex("\\s+"))
            ProcessBuilder(settingsArgs).inheritIO().start()

            val fakeTouch = SystemProperties.get("persist.waydroid.fake_touch", null)
            if (fakeTouch != null && !fakeTouch.isBlank()) {
                val ANSI_RED = "\u001B[31m"
                val ANSI_RESET = "\u001B[0m"
                println(ANSI_RED + "Warning: persist.waydroid.fake_touch=" + fakeTouch + " set" + ANSI_RESET)
                println(ANSI_RED + "Reset it with " + "waydroid prop set persist.waydroid.fake_touch \"\" to avoid issues with XtMapper" + ANSI_RESET)
            } else if (!noLogcat) {
                // Don't flood the console with logcat if warning is being shown
                ProcessBuilder("logcat", "-v", "color", "--pid=" + android.os.Process.myPid()).inheritIO().start()
            }

            if (autoLaunchClientActivity) ProcessBuilder("am", "start", "-a", "android.intent.action.MAIN", "-n",
                ComponentName(mService.context, MainActivity::class.java).flattenToString(),
                "--es", "data",
                MainActivity.SHELL_INIT).inheritIO().start()

        } catch (e: Exception) {
            Log.e(RemoteService.TAG, e.message, e)
            System.exit(1)
        }
        Looper.loop()
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
