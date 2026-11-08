package xtr.keymapper.server

import android.app.ApplicationErrorReport
import android.content.ComponentName
import android.content.Context
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
import xtr.keymapper.server.input.EvdevReader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

class RemoteService(context: Context, internal val isWaylandClient: Boolean = false) : IRemoteService.Stub() {
    private val mouseDevice = MouseDeviceConnection()
    @Volatile
    internal var inputService: InputService? = null
    private var mOnKeyEventListener: OnKeyEventListener? = null
    private var activityObserverService: ActivityObserverService? = null
    internal var nativeLibraryDir: String = System.getProperty("java.library.path").orEmpty()
    private var cursorView: View? = null
    private var TYPE_SECURE_SYSTEM_OVERLAY = 0
    private val mHandler = Handler(Looper.getMainLooper())
    private var mWindowManager: WindowManager? = null
    internal var context: Context = context
    internal var startedFromShell = false

    private var evdevReader: EvdevReader? = null
    private var lastLoggedMouseDevice: String? = null

    // shortcut: single-user check; multi-user/work-profile installs have a different uid per user
    private val appUid: Int by lazy {
        context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
    }

    private fun enforceCaller() {
        if (getCallingUid() != appUid && getCallingPid() != android.os.Process.myPid())
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

    /**
     * Resolves the app's native library directory, starts the selected input monitor, and
     * installs the main thread's crash handler. Wayland mode reads stdin instead of local devices.
     * Each call starts a new monitor.
     *
     * @throws RuntimeException wrapping NameNotFoundException if the app package cannot be found.
     */
    fun init() {
        val pm = context.packageManager
        val packageName = context.packageName
        try {
            val ai = pm.getApplicationInfo(packageName, 0)
            nativeLibraryDir = ai.nativeLibraryDir
            if (isWaylandClient) {
                Thread({ readWaylandEvents() }, "xtmapper-wayland-stdin").start()
            } else {
                evdevReader = EvdevReader(::handleInputLine).also { it.start() }
            }
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

    /** Requests asynchronous shutdown of the local evdev monitor and clears its reference. */
    private fun stopInputReader() {
        evdevReader?.close()
        evdevReader = null
    }

    /**
     * Reads Cage event lines from stdin until EOF. Exceptions from reading or dispatching
     * end the loop and are caught here; stdin is left open.
     */
    private fun readWaylandEvents() {
        try {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = reader.readLine() ?: break
                handleInputLine(line)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wayland input stream stopped", e)
        }
    }

    /**
     * Routes a device-prefixed event through discovery, mapping, and the editor listener.
     * Keyboard grabbing suppresses mapping and editor delivery; stopped mapping still permits
     * keyboard shortcuts. Wayland pointer events are also sent to the mouse handler.
     * Handler/listener Exceptions are caught, and a RemoteException clears the editor listener.
     */
    private fun handleInputLine(line: String) {
        val data = line.split(":".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray() // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
        if (addNewDevices(data)) {
            val service = inputService
            if (service != null) try {
                if (isWaylandClient && data[0].contains("wl_pointer"))
                    service.onWaylandMouseEvent(data[1])

                if (!service.isKeyboardGrabbed) {
                    val k: KeyEventHandler = service.keyEventHandler
                    if (!service.stopEvents) {
                        k.handleEvent(data[1])
                    } else {
                        k.handleKeyboardShortcutEvent(data[1])
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling input event", e)
            }
            if (service == null || !service.isKeyboardGrabbed) {
                try {
                    mOnKeyEventListener?.onKeyEvent(line)
                } catch (e: RemoteException) {
                    mOnKeyEventListener = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying key listener", e)
                }
            }
        }
    }

    /**
     * Discovers local relative-input devices and requests keyboard grabs when enabled.
     * Wayland lines bypass local discovery.
     *
     * @param data event line split on colons, with the device name before the event payload.
     * @return true exactly when there are two parts; the payload is not otherwise validated.
     */
    private fun addNewDevices(data: Array<String>): Boolean {
        if (data.size != 2) return false
        val evdev = data[0]

        val input_event = data[1].split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (isWaylandClient) return true
        if (inputService?.isKeyboardGrabbed == true) {
            if (input_event.size > 1 && input_event[1] == "EV_KEY") {
                inputService?.grabKeyboardDevice(evdev)
            }
        }
        if (input_event.getOrNull(1) == "EV_REL") {
            // Discovery runs on the evdev thread; service lifecycle runs on the main thread.
            if (lastLoggedMouseDevice != evdev) {
                Log.i(TAG, "evdev: queue mouse discovery device=$evdev serviceReady=${inputService != null} thread=${Thread.currentThread().name}")
                lastLoggedMouseDevice = evdev
            }
            if (!mHandler.post { mouseDevice.discover(evdev) }) {
                Log.e(TAG, "evdev: main handler rejected mouse discovery device=$evdev")
            }
        }
        return true
    }

    private val mStartServerDeathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "startServer callback binder died; queuing mouse disconnect")
        mHandler.post {
            mouseDevice.disconnect()
            inputService = null
            stopServer(true)
        }
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
        Log.i(TAG, "startServer: requested wayland=$isWaylandClient display=$displayId pointerMode=${keymapConfig.pointerMode} thread=${Thread.currentThread().name}")
        cb.asBinder().linkToDeath(mStartServerDeathRecipient, 0)
        mHandler.post {
            Log.i(TAG, "startServer: main handler running existingService=${inputService != null}")
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
                Log.i(TAG, "startServer: constructing InputService")
                val newService = InputService(
                    profile, keymapConfig, cb, screenWidth, screenHeight,
                    cursorView, isWaylandClient, displayId
                )
                newService.keyListener = { line ->
                    try {
                        mOnKeyEventListener?.onKeyEvent(line)
                    } catch (e: RemoteException) {
                        mOnKeyEventListener = null
                    }
                }
                Log.i(TAG, "startServer: InputService constructed id=${System.identityHashCode(newService)}")
                inputService = newService
                if (!isWaylandClient) {
                    Log.i(TAG, "startServer: setting mouse lock before connecting discovered device")
                    newService.setMouseLock(true)
                    mouseDevice.connect { device ->
                        Log.i(TAG, "mouse openDevice: calling JNI device=$device service=${System.identityHashCode(newService)}")
                        val fd = newService.openDevice(device)
                        Log.i(TAG, "mouse openDevice: JNI returned fd=$fd device=$device service=${System.identityHashCode(newService)}")
                        fd >= 0
                    }
                    Log.i(TAG, "startServer: mouse connection initialized isOpen=${mouseDevice.isOpen}")
                } else {
                    Log.i(TAG, "startServer: Wayland mode; skipping evdev mouse connection")
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
        val service = inputService
        Log.i(TAG, "stopServer: exitProcess=$exitProcess startedFromShell=$startedFromShell serviceReady=${service != null} mouseOpen=${mouseDevice.isOpen} wayland=$isWaylandClient")
        if (service != null) try {
            service.callback.disablePointer()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }

        if (!startedFromShell && exitProcess) {
            stopInputReader()
            System.exit(0)
        } else if (service != null && !isWaylandClient) {
            service.stopEvents = true
            service.hideCursor()
            service.stop()
            if (mouseDevice.isOpen) {
                Log.i(TAG, "stopServer: stopping native mouse thread")
                service.stopMouse()
                Log.i(TAG, "stopServer: native mouse thread stopped")
            } else {
                Log.i(TAG, "stopServer: skipping mouse stop because no device opened successfully")
            }
            mouseDevice.disconnect()
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

        fun loadLibraries() {
            System.loadLibrary("mouse_read")
            System.loadLibrary("mouse_cursor")
            System.loadLibrary("touchpad_direct")
            System.loadLibrary("touchpad_relative")
            System.loadLibrary("keyboard_grab")
        }
    }
}
