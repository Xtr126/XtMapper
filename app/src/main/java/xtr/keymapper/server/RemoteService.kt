package xtr.keymapper.server

import android.app.ApplicationErrorReport
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import xtr.keymapper.ActivityObserver
import xtr.keymapper.BuildConfig
import xtr.keymapper.IRemoteService
import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.OnKeyEventListener
import xtr.keymapper.R
import xtr.keymapper.Utils
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.databinding.CursorBinding
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.server.event.KeyEventHandler
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

class RemoteService(context: Context) : IRemoteService.Stub() {
    private var currentDevice = ""
    internal var inputService: InputService? = null
    private var mOnKeyEventListener: OnKeyEventListener? = null
    internal var isWaylandClient = false
    private var activityObserverService: ActivityObserverService? = null
    internal var nativeLibraryDir: String = System.getProperty("java.library.path")
    private var cursorView: View? = null
    private var TYPE_SECURE_SYSTEM_OVERLAY = 0
    private val mHandler = Handler(Looper.getMainLooper())
    private var mWindowManager: WindowManager? = null
    internal var context: Context = context
    internal var startedFromShell = false

    // shortcut: single-user check; multi-user/work-profile installs have a different uid per user
    private val appUid: Int by lazy {
        context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
    }

    private fun enforceCaller() {
        if (Binder.getCallingUid() != appUid)
            throw SecurityException("XtMapper service access denied")
    }

    init {
        loadLibraries()
        init()
    }

    private fun getWindowManager(displayId: Int): WindowManager {
        return try {
            prepareCursorOverlayWindow(displayId)
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
            context.getSystemService(WindowManager::class.java)
        }
    }

    fun init() {
        val pm = context.packageManager
        val packageName = context.packageName
        try {
            val ai = pm.getApplicationInfo(packageName, 0)
            nativeLibraryDir = ai.nativeLibraryDir
            if (!isWaylandClient) Thread { start_getevent() }.start()
            else mHandler.post { start_getevent() }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, e.message, e)
            throw RuntimeException(e)
        }

        Looper.getMainLooper().thread.setUncaughtExceptionHandler { t, e ->
            try {
                val crashInfo = ApplicationErrorReport.CrashInfo(e)

                ProcessBuilder(
                    "am", "start", "-a", "android.intent.action.MAIN", "-n",
                    ComponentName(context, MainActivity::class.java).flattenToString(),
                    "--es", "data",
                    crashInfo.exceptionMessage + "\n" +
                            crashInfo.exceptionClassName + "\n" +
                            crashInfo.stackTrace + "\n" +
                            crashInfo.throwClassName + "\n" +
                            crashInfo.throwFileName + "\n" +
                            crashInfo.throwLineNumber + "\n" +
                            crashInfo.throwMethodName
                ).start()
            } catch (ex: Exception) {
                Log.e(TAG, ex.message, ex)
            }
            System.exit(1)
        }
    }

    private fun addCursorView() {
        val view = cursorView ?: return

        if (view.isAttachedToWindow) {
            view.visibility = View.VISIBLE
        } else {
            val params = Utils.getPointerLayoutParams(TYPE_SECURE_SYSTEM_OVERLAY)
            try {
                mWindowManager!!.addView(view, params)
            } catch (e: IllegalStateException) { // A14 QPR3 issue https://gist.github.com/RikkaW/be3fe4178903702c54ec73b2fc1187fe
                cursorView = null
                Log.e(TAG, e.message, e)
            }
        }
    }

    @Throws(
        NoSuchMethodException::class,
        NoSuchFieldException::class,
        IllegalAccessException::class
    )
    fun prepareCursorOverlayWindow(displayId: Int): WindowManager {
        TYPE_SECURE_SYSTEM_OVERLAY = WindowManager.LayoutParams::class.java
            .getField("TYPE_SECURE_SYSTEM_OVERLAY").getInt(null)

        val display: Display =
            context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        this.context = this.context.createDisplayContext(display)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.context = this.context.createWindowContext(display, TYPE_SECURE_SYSTEM_OVERLAY, null)
        }
        /*
        * We obtained an instance of WindowManager configured to show windows on the given display.
        * Now call getSystemService(Class) on the returned window context.
        */
        val windowManager: WindowManager = this.context.getSystemService(WindowManager::class.java)

        val layoutInflater: LayoutInflater = this.context.getSystemService(LayoutInflater::class.java)
        this.context.setTheme(R.style.Theme_XtMapper)
        cursorView = CursorBinding.inflate(layoutInflater).root

        val sWindowToken = Binder()
        val setDefaultTokenMethod: Method =
            windowManager.javaClass.getMethod("setDefaultToken", IBinder::class.java)
        setDefaultTokenMethod.invoke(windowManager, sWindowToken)
        return windowManager
    }

    /**
     * Executes getevent command and processes the output or reads from stdin if wayland client
     */
    private fun start_getevent() {
        try {
            val getevent: BufferedReader
            if (isWaylandClient) {
                getevent = BufferedReader(InputStreamReader(System.`in`))
            } else {
                getevent = Utils.geteventStream(nativeLibraryDir)
            }
            var line: String?
            while (getevent.readLine().also { line = it } != null) {
                val data = line!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray() // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
                if (addNewDevices(data)) {
                    val service = inputService
                    if (service != null) try {
                        if (isWaylandClient && data[0].contains("wl_pointer"))
                            service.onWaylandMouseEvent(data[1])

                        val k: KeyEventHandler = service.keyEventHandler
                        if (!service.stopEvents) {
                            k.handleEvent(data[1])
                        } else {
                            k.handleKeyboardShortcutEvent(data[1])
                        }
                        mOnKeyEventListener?.onKeyEvent(line)
                    } catch (e: RemoteException) {
                        throw RuntimeException(e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
    }

    /**
     * @param data split output of getevent command
     * @return true if output is valid for processing
     */
    private fun addNewDevices(data: Array<String>): Boolean {
        if (data.size != 2) return false
        val evdev = data[0]

        val input_event = data[1].split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (isWaylandClient) return true
        if (currentDevice != evdev)
            if (input_event[1] == "EV_REL") {
                println("add mouse device: $evdev")
                inputService?.openDevice(evdev)
                currentDevice = evdev
            }
        return true
    }

    private val mStartServerDeathRecipient = IBinder.DeathRecipient {
        inputService = null
        stopServer()
    }

    /**
     * Called by client to start the remote server.
     *
     * @param profile  The keymap profile
     * @param keymapConfig Some configurations
     * @param cb  The instance used to callback to remote service
     * @param screenHeight Screen resolution (vertical)
     * @param screenWidth  Screen resolution (horizontal)
     */
    @Throws(RemoteException::class)
    override fun startServer(
        profile: KeymapProfile,
        keymapConfig: KeymapConfig,
        cb: IRemoteServiceCallback,
        screenWidth: Int,
        screenHeight: Int,
        displayId: Int
    ) {
        enforceCaller()
        cb.asBinder().linkToDeath(mStartServerDeathRecipient, 0)
        mHandler.post {
            val service = inputService
            if (service != null) {
                if (isWaylandClient) {
                    service.hideCursor()
                    service.stop()
                }
                stopServer(false)
            }
            mWindowManager = getWindowManager(displayId)

            if (keymapConfig.pointerMode != KeymapConfig.POINTER_SYSTEM) {
                addCursorView()
            } else {
                cursorView = null
            }
            try {
                val newService = InputService(
                    profile, keymapConfig, cb, screenWidth, screenHeight,
                    cursorView, isWaylandClient, displayId
                )
                inputService = newService
                if (!isWaylandClient) {
                    newService.setMouseLock(true)
                    newService.openDevice(currentDevice)
                }
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
            // Launch app/game
            if (profile.packageName != BuildConfig.APPLICATION_ID && keymapConfig.disableAutoProfiling) {
                val launchIntent =
                    context.packageManager.getLaunchIntentForPackage(profile.packageName)
                if (launchIntent != null && launchIntent.component != null) try {
                    ProcessBuilder(
                        "am", "start", "-a", "android.intent.action.MAIN", "-n",
                        launchIntent.component!!.flattenToString()
                    ).start()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
        }
    }

    override fun destroy() {
        enforceCaller()
        stopServer(true)
    }

    override fun stopServer() {
        enforceCaller()
        mHandler.post { stopServer(true) }
    }

    private fun stopServer(exitProcess: Boolean) {
        // Restore enforcing only if this app actually relaxed SELinux (shell entry point);
        // startedFromShell is also set by the system-app path, which never changes SELinux
        if (exitProcess && selinuxRelaxed) {
            selinuxRelaxed = false
            try {
                ProcessBuilder("setenforce", "1").start()
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
            }
        }
        val service = inputService
        if (service != null) try {
            service.callback.disablePointer()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }

        if (!startedFromShell && exitProcess) {
            System.exit(0)
        } else if (service != null && !isWaylandClient) {
            service.stopEvents = true
            service.hideCursor()
            service.stop()
            service.stopMouse()
            service.stopTouchpad()
            service.destroyUinputDev()
            service.callback.asBinder().unlinkToDeath(mStartServerDeathRecipient, 0)
            inputService = null
        }
    }

    private val mKeyEventListenerDeathRecipient = IBinder.DeathRecipient {
        mOnKeyEventListener = null
    }

    @Throws(RemoteException::class)
    override fun registerOnKeyEventListener(l: OnKeyEventListener) {
        enforceCaller()
        l.asBinder().linkToDeath(mKeyEventListenerDeathRecipient, 0)
        mOnKeyEventListener = l
    }

    override fun unregisterOnKeyEventListener(l: OnKeyEventListener?) {
        enforceCaller()
        l?.asBinder()?.unlinkToDeath(mKeyEventListenerDeathRecipient, 0)
        mOnKeyEventListener = null
    }

    override fun registerActivityObserver(callback: ActivityObserver) {
        enforceCaller()
        activityObserverService?.stop()
        activityObserverService = ActivityObserverService(callback)
    }

    override fun unregisterActivityObserver(callback: ActivityObserver) {
        enforceCaller()
        activityObserverService?.stop()
        activityObserverService = null
    }

    /**
     * Used to temporary stop the keymapping.
     */
    override fun pauseMouse() {
        enforceCaller()
        val service = inputService
        if (service != null)
            if (!service.stopEvents) service.pauseResumeKeymap()
    }

    override fun resumeMouse() {
        enforceCaller()
        val service = inputService
        if (service != null)
            if (service.stopEvents)
                service.pauseResumeKeymap()
    }

    /**
     * Used to refresh by requesting new keymap from the user app
     */
    override fun reloadKeymap() {
        enforceCaller()
        inputService?.reloadKeymap()
    }

    override fun isActive(): Boolean {
        enforceCaller()
        return inputService != null
    }

    companion object {
        const val TAG = "xtmapper-server"

        // shortcut: process-wide flag; only meaningful while this server process lives (per-process state by design)
        internal var selinuxRelaxed = false

        fun loadLibraries() {
            System.loadLibrary("mouse_read")
            System.loadLibrary("mouse_cursor")
            System.loadLibrary("touchpad_direct")
            System.loadLibrary("touchpad_relative")
        }
    }
}
