package xtr.keymapper.server

import android.util.Log
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
import android.os.Process
import android.os.RemoteException
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
import xtr.keymapper.log.DeviceTelemetry
import xtr.keymapper.log.Role
import xtr.keymapper.log.SessionStates
import xtr.keymapper.log.XtLogger
import xtr.keymapper.server.event.KeyEventHandler
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

class RemoteService @JvmOverloads constructor(
    context: Context,
    initialIsWaylandClient: Boolean = false
) : IRemoteService.Stub() {
    private val mouseDevice = MouseDeviceConnection()
    internal val mInputService = ClientServiceSlot<ClientSession, InputService>()
    private val touchpadSessions = TouchpadSessionRegistry(RealTouchpadNativeOps)
    private var mOnKeyEventListener: OnKeyEventListener? = null
    internal var isWaylandClient = initialIsWaylandClient
    private var activityObserverService: ActivityObserverService? = null
    internal var nativeLibraryDir: String = System.getProperty("java.library.path").orEmpty()
    private var cursorView: View? = null
    private var TYPE_SECURE_SYSTEM_OVERLAY = 0
    private val mHandler = Handler(Looper.getMainLooper())
    private val mouseDeviceCoalescer = MouseDeviceCoalescer(
        schedule = { task ->
            if (!mHandler.post { task() }) {
                Log.e(TAG, "getevent: main handler rejected mouse discovery drain")
            }
        },
        process = { device -> mouseDevice.discover(device) },
    )
    private var mWindowManager: WindowManager? = null
    internal var context: Context = context
    internal var startedFromShell = false

    private var geteventProcess: java.lang.Process? = null
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
        if (!XtLogger.isInitialized) {
            when (Process.myUid()) {
                2000 -> XtLogger.initStandalone(Role.SHIZUKU)
                0 -> XtLogger.initStandalone(Role.ROOT)
                else -> Unit
            }
        }
        try {
            loadLibraries()
        } catch (t: Throwable) {
            XtLogger.fatal(TAG, "native library load failed", t)
            throw t
        }
        init()
    }

    private fun getWindowManager(displayId: Int): WindowManager {
        return try {
            prepareCursorOverlayWindow(displayId)
        } catch (e: Exception) {
            XtLogger.error(TAG, "Failed to prepare cursor overlay window; falling back to default WindowManager", e)
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
            XtLogger.error(TAG, "Unable to resolve app native library directory", e)
            throw RuntimeException(e)
        }

        Looper.getMainLooper().thread.setUncaughtExceptionHandler { t, e ->
            // Record the terminal failure first (bounded flush) before attempting the UI relay.
            try {
                XtLogger.fatal(TAG, "uncaught exception on thread ${t.name}", e)
            } catch (ignored: Exception) {
            }
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
                XtLogger.error(TAG, "Failed to relay the server crash to MainActivity", ex)
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
                XtLogger.error(TAG, "Failed to attach cursor overlay view", e)
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

    private fun stop_getevent() {
        try {
            geteventProcess?.destroy()
            geteventProcess = null
        } catch (_: Exception) {}
    }

    private fun start_getevent() {
        try {
            val getevent: BufferedReader
            if (isWaylandClient) {
                getevent = BufferedReader(InputStreamReader(System.`in`))
            } else {
                val proc = Utils.geteventStream(nativeLibraryDir)
                geteventProcess = proc
                getevent = BufferedReader(InputStreamReader(proc.inputStream))
            }
            var line: String?
            while (getevent.readLine().also { line = it } != null) {
                val data = line!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray() // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
                if (addNewDevices(data)) {
                    val service = mInputService.get()
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
                        XtLogger.error(TAG, "Error handling input event", e)
                    }
                    if (service == null || !service.isKeyboardGrabbed) {
                        try {
                            mOnKeyEventListener?.onKeyEvent(line)
                        } catch (e: RemoteException) {
                            mOnKeyEventListener = null
                        } catch (e: Exception) {
                            XtLogger.error(TAG, "Error notifying key listener", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            XtLogger.error(TAG, "Input event reader failed", e)
        }
    }

    private fun addNewDevices(data: Array<String>): Boolean {
        if (data.size != 2) return false
        val evdev = data[0]

        val input_event = data[1].split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (isWaylandClient) return true
        if (mInputService.get()?.isKeyboardGrabbed == true) {
            if (input_event.size > 1 && input_event[1] == "EV_KEY") {
                mInputService.get()?.grabKeyboardDevice(evdev)
            }
        }
        if (input_event.getOrNull(1) == "EV_REL") {
            if (lastLoggedMouseDevice != evdev) {
                Log.i(TAG, "getevent: queue mouse discovery device=$evdev serviceReady=${mInputService.get() != null} thread=${Thread.currentThread().name}")
                lastLoggedMouseDevice = evdev
            }
            mouseDeviceCoalescer.request(evdev)
        }
        return true
    }

    private val mClientDeathRegistrations = ClientDeathRegistry<IBinder>()
    private val mStartRequestLock = Any()
    private val mStartRequestSequence = AtomicLong()
    @Volatile
    private var destroyRequested = false

    private fun isCurrentStartRequest(
        clientBinder: IBinder,
        deathRecipient: IBinder.DeathRecipient,
        requestId: Long
    ): Boolean {
        return !destroyRequested &&
            requestId == mStartRequestSequence.get() &&
            clientBinder.isBinderAlive &&
            mClientDeathRegistrations.isCurrent(clientBinder, deathRecipient)
    }

    private fun notifyClientDeath(clientBinder: IBinder, deathRecipient: IBinder.DeathRecipient) {
        Log.w(TAG, "startServer callback binder died; queuing mouse disconnect")
        if (!mClientDeathRegistrations.onDeath(clientBinder, deathRecipient)) {
            XtLogger.info(TAG, "ignored death notification from a superseded client registration")
            return
        }
        val session = ClientSession(clientBinder, deathRecipient)
        if (isWaylandClient) {
            val (service, superseded) = mInputService.claimForShutdown(session)
            if (superseded) {
                XtLogger.info(TAG, "ignored death notification of a replaced client session")
                return
            }
            try {
                try {
                    service?.stop()
                } catch (e: Exception) {
                    XtLogger.error(TAG, "Failed to stop input service after client binder death", e)
                }
                // The slot is already empty, avoiding callbacks into the dead Binder.
                stopServer(true)
            } finally {
                // Retry waiters are notified only after every terminal commitment completes.
                mInputService.releaseTerminal()
            }
        } else {
            mHandler.post {
                val owner = mInputService.owner()
                if (owner != null && owner != session) {
                    XtLogger.info(TAG, "ignored death notification of a replaced client session")
                    return@post
                }
                stopServer(true, clientAlive = false)
            }
        }
    }

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
        XtLogger.info(TAG, "startServer requested wayland=$isWaylandClient display=$displayId pointerMode=${keymapConfig.pointerMode}")
        val clientBinder = cb.asBinder()
        val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                notifyClientDeath(clientBinder, this)
            }
        }
        val request = synchronized(mStartRequestLock) {
            if (destroyRequested) {
                null
            } else {
                val previous = mClientDeathRegistrations.registerAndLink(
                    clientBinder,
                    deathRecipient
                ) {
                    clientBinder.linkToDeath(deathRecipient, 0)
                }
                // A failed link must not supersede a still-valid pending request.
                mStartRequestSequence.incrementAndGet() to previous
            }
        } ?: run {
            XtLogger.info(TAG, "ignored start request after service destruction")
            return
        }
        val (requestId, superseded) = request
        superseded?.let { clientBinder.unlinkToDeath(it, 0) }

        mHandler.post {
            startServerOnHandler(
                profile,
                keymapConfig,
                cb,
                screenWidth,
                screenHeight,
                displayId,
                clientBinder,
                deathRecipient,
                requestId
            )
        }
    }

    private fun startServerOnHandler(
        profile: KeymapProfile,
        keymapConfig: KeymapConfig,
        cb: IRemoteServiceCallback,
        screenWidth: Int,
        screenHeight: Int,
        displayId: Int,
        clientBinder: IBinder,
        deathRecipient: IBinder.DeathRecipient,
        requestId: Long
    ) {
        Log.i(TAG, "startServer: main handler running existingService=${mInputService.get() != null}")
        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            XtLogger.info(TAG, "ignored stale start request displayId=$displayId")
            return
        }

        val retryStart: () -> Unit = {
            mHandler.post {
                startServerOnHandler(
                    profile,
                    keymapConfig,
                    cb,
                    screenWidth,
                    screenHeight,
                    displayId,
                    clientBinder,
                    deathRecipient,
                    requestId
                )
            }
        }
        if (mInputService.deferUntilAvailable(retryStart)) {
            XtLogger.info(TAG, "deferred start request displayId=$displayId until shutdown completes")
            return
        }

        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            XtLogger.info(TAG, "ignored stale start request before service creation displayId=$displayId")
            return
        }

        val service = mInputService.get()
        if (service != null) {
            if (isWaylandClient) {
                service.hideCursor()
                service.stop()
            }
            stopServer(false)
        }

        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            cursorView?.visibility = View.GONE
            XtLogger.info(TAG, "ignored start request superseded during previous-session shutdown")
            return
        }

        mWindowManager = getWindowManager(displayId)
        if (keymapConfig.pointerMode != KeymapConfig.POINTER_SYSTEM) {
            addCursorView()
        } else {
            cursorView = null
        }

        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            cursorView?.visibility = View.GONE
            XtLogger.info(TAG, "ignored stale start request before InputService construction")
            return
        }

        Log.i(TAG, "startServer: constructing InputService")
        val newService = try {
            InputService(
                profile, keymapConfig, cb, screenWidth, screenHeight,
                cursorView, isWaylandClient, displayId, touchpadSessions
            )
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
        Log.i(TAG, "startServer: InputService constructed id=${System.identityHashCode(newService)}")
        newService.keyListener = { line ->
            try {
                mOnKeyEventListener?.onKeyEvent(line)
            } catch (e: RemoteException) {
                mOnKeyEventListener = null
            }
        }

        val session = ClientSession(clientBinder, deathRecipient)

        startTouchpadFor(newService)

        val admitted = synchronized(mStartRequestLock) {
            !destroyRequested && mClientDeathRegistrations.runIfCurrent(
                clientBinder,
                deathRecipient,
                isAlive = {
                    !destroyRequested &&
                        requestId == mStartRequestSequence.get() &&
                        clientBinder.isBinderAlive
                }
            ) {
                mInputService.install(session, newService)
            }
        }
        if (!admitted) {
            discardUninstalledService(newService)
            if (isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
                if (!mInputService.deferUntilAvailable(retryStart)) {
                    retryStart()
                }
            }
            XtLogger.warn(TAG, "startServer admission rejected; stale requests are discarded and blocked requests are deferred")
            return
        }

        if (!mInputService.runIfOwner(session) {
            // Telemetry must not outlive the exact session that admitted it.
            DeviceTelemetry.start(context)
        }) {
            XtLogger.warn(TAG, "session committed for shutdown or replaced after admission; telemetry startup withheld")
            return
        }

        if (!isWaylandClient) {
            Log.i(TAG, "startServer: setting mouse lock before connecting discovered device")
            newService.setMouseLock(true)
            mouseDevice.connect { device ->
                Log.i(TAG, "mouse openDevice: calling JNI device=$device service=${System.identityHashCode(newService)}")
                val fd = newService.openMouseDevice(device)
                Log.i(TAG, "mouse openDevice: JNI returned fd=$fd device=$device service=${System.identityHashCode(newService)}")
                fd >= 0
            }
            Log.i(TAG, "startServer: mouse connection initialized isOpen=${mouseDevice.isOpen}")
        } else {
            Log.i(TAG, "startServer: Wayland mode; skipping evdev mouse connection")
        }

        // Launch app/game only after this request has actually installed its service.
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

    private fun startTouchpadFor(service: InputService) {
        try {
            service.startTouchpad()
        } catch (t: Throwable) {
            try {
                service.stopTouchpad()
            } catch (e: Exception) {
                XtLogger.error(TAG, "Touchpad rollback after failed start threw", e)
            }
            throw t
        }
    }

    private fun discardUninstalledService(service: InputService) {
        cursorView?.visibility = View.GONE
        OrderedCleanup.run(
            "stop rejected input service" to { service.stop() },
            "stop mouse reader for rejected service" to { service.stopMouseIfStarted() },
            "stop touchpad for rejected service" to { service.stopTouchpad() },
            "destroy uinput for rejected service" to { service.destroyUinputDev() },
        )
    }

    override fun destroy() {
        enforceCaller()
        synchronized(mStartRequestLock) {
            destroyRequested = true
            mStartRequestSequence.incrementAndGet()
        }
        if (isWaylandClient) {
            val session = mInputService.owner()
            if (session == null) {
                stopServer(true)
                return
            }

            val (service, superseded) = mInputService.claimForShutdown(session)
            if (superseded) {
                XtLogger.info(TAG, "ignored destroy request for a replaced client session")
                return
            }
            try {
                if (service != null) {
                    try {
                        service.callback.disablePointer()
                    } catch (e: RemoteException) {
                        XtLogger.warn(TAG, "Client Binder unavailable during Wayland destroy; continuing cleanup", e)
                    }
                    try {
                        service.stop()
                    } catch (e: Exception) {
                        XtLogger.error(TAG, "Failed to stop input service during Wayland destroy", e)
                    }
                }
                stopServer(true, clientAlive = false)
            } finally {
                mInputService.releaseTerminal()
            }
        } else {
            mHandler.post { stopServer(true) }
        }
    }

    override fun stopServer() {
        enforceCaller()
        mHandler.post { stopServer(true) }
    }

    private fun stopServer(exitProcess: Boolean, clientAlive: Boolean = true) {
        DeviceTelemetry.stop()
        val service = mInputService.get()
        Log.i(TAG, "stopServer: exitProcess=$exitProcess startedFromShell=$startedFromShell serviceReady=${service != null} mouseOpen=${mouseDevice.isOpen} wayland=$isWaylandClient clientAlive=$clientAlive")
        if (service != null && clientAlive) {
            try {
                service.callback.disablePointer()
            } catch (e: RemoteException) {
                XtLogger.warn(TAG, "Client Binder unavailable during pointer shutdown; continuing cleanup", e)
            }
        }

        if (!startedFromShell && exitProcess) {
            XtLogger.info(TAG, "server stopped; exiting process")
            // END marker before the existing System.exit; endSession is bounded.
            XtLogger.endSession(SessionStates.STATUS_SERVICE_STOP)
            stop_getevent()
            System.exit(0)
        } else if (service != null && !isWaylandClient) {
            service.stopEvents = true
            CursorShutdown.hideThenCleanup(
                hideCursor = { service.hideCursor() },
                cleanup = {
                    OrderedCleanup.run(
                        "stop input service" to { service.stop() },
                        "stop mouse reader" to {
                            Log.i(TAG, "stopServer: stopping native mouse reader if started")
                            service.stopMouseIfStarted()
                            Log.i(TAG, "stopServer: native mouse cleanup returned")
                        },
                        "stop touchpad" to { service.stopTouchpad() },
                        "destroy uinput" to { service.destroyUinputDev() },
                        "mouse device connection" to { mouseDevice.disconnect() },
                        "clear input service slot" to { mInputService.clear() },
                    )
                }
            )
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

    override fun pauseMouse() {
        enforceCaller()
        mHandler.post {
            val service = mInputService.get()
            if (service != null && !service.stopEvents) {
                service.pauseResumeKeymap()
            }
        }
    }

    override fun resumeMouse() {
        enforceCaller()
        // Keep pause/resume state changes ordered with startServer() on the same handler.
        mHandler.post {
            val service = mInputService.get()
            if (service != null && service.stopEvents) {
                service.pauseResumeKeymap()
            }
        }
    }

    override fun reloadKeymap() {
        enforceCaller()
        mInputService.get()?.reloadKeymap()
    }

    override fun isActive(): Boolean {
        enforceCaller()
        return mInputService.get() != null
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
