package xtr.keymapper.server

import android.util.Log
import android.app.ApplicationErrorReport
import android.content.ComponentName
import android.content.Context
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
import xtr.keymapper.InputEventCodes
import xtr.keymapper.wayland.WaylandCaptureNative
import kotlin.math.roundToInt
import xtr.keymapper.IRemoteService
import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.IShellAuthenticationCallback
import xtr.keymapper.OnKeyEventListener
import xtr.keymapper.R
import xtr.keymapper.Utils
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.databinding.CursorBinding
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import org.lsposed.hiddenapibypass.HiddenApiBypass
import xtr.keymapper.log.DeviceTelemetry
import xtr.keymapper.log.Role
import xtr.keymapper.log.SessionStates
import xtr.keymapper.log.XtLogger
import xtr.keymapper.server.event.KeyEventHandler
import xtr.keymapper.server.input.EvdevReader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

class RemoteService @JvmOverloads constructor(
    context: Context,
    initialIsWaylandClient: Boolean = false,
    private val nativeWaylandFd: Int? = null,
) : IRemoteService.Stub() {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to add hidden API exemptions", t)
            }
        }
    }
    private val mouseDevice = MouseDeviceConnection()
    internal val mInputService = ClientServiceSlot<ClientSession, InputService>()
    private val touchpadSessions = TouchpadSessionRegistry(RealTouchpadNativeOps)
    private var mOnKeyEventListener: OnKeyEventListener? = null
    private val usesLegacyWaylandInput = initialIsWaylandClient
    internal val isWaylandClient = usesLegacyWaylandInput || nativeWaylandFd != null

    override fun isNativeWaylandBackend(): Boolean {
        enforceCaller()
        return nativeWaylandFd != null
    }

    override fun isNativeWaylandCaptureEnabled(): Boolean {
        enforceCaller()
        return nativeWaylandFd != null && BuildConfig.DEBUG &&
            System.getenv("XTMAPPER_EXPERIMENTAL_WAYLAND_CAPTURE") == "1"
    }
    private var activityObserverService: ActivityObserverService? = null
    private var cursorView: View? = null
    private var TYPE_SECURE_SYSTEM_OVERLAY = 0
    private val mHandler = Handler(Looper.getMainLooper())
    private var waylandCapture: WaylandCaptureNative? = null
    private var waylandGeneration = 0L
    private var waylandState = 0
    private var waylandWantsLock = false
    private var waylandWidth = 0
    private var waylandHeight = 0
    private val mouseDeviceCoalescer = MouseDeviceCoalescer(
        schedule = { task ->
            if (!mHandler.post { task() }) {
                Log.e(TAG, "evdev: main handler rejected mouse discovery drain")
            }
        },
        process = { device -> mouseDevice.discover(device) },
    )
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
        if (getCallingUid() != appUid && getCallingPid() != Process.myPid())
            throw SecurityException("XtMapper service access denied")
    }

    override fun authenticateShellClient(callback: IShellAuthenticationCallback, challenge: Long) {
        enforceCaller()
        if (Process.myUid() != Process.SHELL_UID) {
            throw SecurityException("Only the shell process can authenticate this service Binder")
        }
        callback.onShellServiceAuthenticated(challenge)
    }

    /**
     * Returns the Binder protocol version used by this daemon.
     */
    override fun getVersion(): Int {
        return PROTOCOL_VERSION
    }

    override fun grantOverlayPermission(): Boolean {
        enforceCaller()
        return OverlayPermission.grant()
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

    /** Starts the selected input monitor: stdin for Wayland, local evdev otherwise. */
    fun init() {
        when {
            nativeWaylandFd != null -> initWaylandCapture(nativeWaylandFd)
            usesLegacyWaylandInput ->
                Thread({ readWaylandEvents() }, "xtmapper-wayland-stdin").start()
            else -> evdevReader = EvdevReader(::handleInputLine).also { it.start() }
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

    /**
     * Prepares and adds the cursor overlay view to the WindowManager
     * with an empty touchable region.
     */
    private fun addCursorView() {
        val view = cursorView ?: return

        if (view.isAttachedToWindow) {
            view.visibility = View.VISIBLE
        } else {
            val params = Utils.getPointerLayoutParams(TYPE_SECURE_SYSTEM_OVERLAY)
            try {
                Utils.applyEmptyTouchableRegion(view)
                mWindowManager!!.addView(view, params)
            } catch (e: IllegalStateException) { // A14 QPR3 issue https://gist.github.com/RikkaW/be3fe4178903702c54ec73b2fc1187fe
                cursorView = null
                XtLogger.error(TAG, "Failed to attach cursor overlay view", e)
            }
        }
    }

    private fun hideOverlayCursor() {
        // Keep View mutations on the main thread. The caller claims the session first,
        // so this hide is queued before any replacement session can install its cursor.
        mHandler.post { cursorView?.visibility = View.GONE }
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

    /** Requests asynchronous shutdown of the local evdev monitor and clears its reference. */
    private fun stopInputReader() {
        evdevReader?.close()
        evdevReader = null
    }

    private fun initWaylandCapture(fd: Int) {
        waylandCapture = WaylandCaptureNative(fd, object : WaylandCaptureNative.Callback {
            override fun onState(generation: Long, state: Int) {
                mHandler.post {
                    if (generation == waylandGeneration) {
                        val previous = waylandState
                        waylandState = state
                        Log.i(TAG, "Wayland capture generation=$generation state=$state")
                        val lost = previous and state.inv()
                        val lostRequestedLock = waylandWantsLock &&
                            lost and WaylandCaptureNative.POINTER_LOCKED != 0
                        if (lost and WAYLAND_INPUT_FOCUS != 0 || lostRequestedLock) {
                            mInputService.get()?.releaseWaylandEvents()
                            if (waylandWantsLock && state and WaylandCaptureNative.MAPPED != 0) {
                                waylandWantsLock = false
                                waylandCapture?.setVisible(true, generation, false, waylandWidth, waylandHeight)
                            }
                        }
                    }
                }
            }

            override fun onEvent(generation: Long, type: Int, code: Int, x: Double, y: Double) {
                mHandler.post {
                    if (generation != waylandGeneration) return@post
                    val service = mInputService.get() ?: return@post
                    if (service.stopEvents || waylandState and WaylandCaptureNative.MAPPED == 0) return@post
                    val focus = if (type == WaylandCaptureNative.KEY) {
                        WaylandCaptureNative.KEYBOARD_FOCUS
                    } else {
                        WaylandCaptureNative.POINTER_FOCUS
                    }
                    if (waylandState and focus == 0) return@post
                    when (type) {
                        WaylandCaptureNative.KEY -> service.onWaylandKey(code, x != 0.0)
                        WaylandCaptureNative.BUTTON -> service.onWaylandButton(code, x != 0.0)
                        WaylandCaptureNative.ABSOLUTE -> if (!waylandWantsLock) {
                            service.mouseEventHandler.absolutePosition(
                                (x.coerceIn(0.0, 1.0) * (waylandWidth - 1)).roundToInt(),
                                (y.coerceIn(0.0, 1.0) * (waylandHeight - 1)).roundToInt(),
                            )
                        }
                        WaylandCaptureNative.RELATIVE -> if (waylandState and WaylandCaptureNative.POINTER_LOCKED != 0) {
                            service.sendMouseEvent(InputEventCodes.REL_X, x.roundToInt())
                            service.sendMouseEvent(InputEventCodes.REL_Y, y.roundToInt())
                        }
                        WaylandCaptureNative.SCROLL -> service.sendMouseEvent(InputEventCodes.REL_WHEEL, y.roundToInt())
                    }
                    val wantsLock = service.mouseEventHandler.mouseAimActive
                    if (!service.stopEvents && wantsLock != waylandWantsLock) {
                        waylandWantsLock = wantsLock
                        waylandCapture?.setVisible(true, waylandGeneration, wantsLock, waylandWidth, waylandHeight)
                    }
                }
            }
        })
    }

    private fun setWaylandVisible(visible: Boolean, service: InputService?) {
        val capture = waylandCapture ?: return
        waylandGeneration++
        waylandState = 0
        waylandWantsLock = visible && service?.mouseEventHandler?.mouseAimActive == true
        try {
            capture.setVisible(visible, waylandGeneration, waylandWantsLock, waylandWidth, waylandHeight)
        } finally {
            if (!visible) service?.releaseWaylandEvents()
        }
    }

    /** Reads Cage event lines until EOF and leaves process-owned stdin open. */
    private fun readWaylandEvents() {
        try {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = reader.readLine() ?: break
                handleInputLine(line)
            }
        } catch (e: Exception) {
            XtLogger.error(TAG, "Wayland input stream stopped", e)
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

    /** Discovers local relative-input devices and queues keyboard grabs; Wayland bypasses discovery. */
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
                Log.i(TAG, "evdev: queue mouse discovery device=$evdev serviceReady=${mInputService.get() != null} thread=${Thread.currentThread().name}")
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
        if (usesLegacyWaylandInput) {
            val installedSession = mInputService.owner()
            if (installedSession != null && installedSession.binder != clientBinder) {
                XtLogger.info(TAG, "ignored death notification of a replaced client Binder")
                return
            }
            // A repeated start from the same callback Binder installs a new death recipient
            // before its queued replacement runs. Keep the old service covered by this death.
            val sessionToStop = installedSession ?: session
            val (service, superseded) = mInputService.claimForShutdown(sessionToStop)
            if (superseded) {
                XtLogger.info(TAG, "ignored death notification of a replaced client session")
                return
            }
            hideOverlayCursor()
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
                if (owner != null && owner.binder != clientBinder) {
                    XtLogger.info(TAG, "ignored death notification of a replaced client Binder")
                    return@post
                }
                stopServer(true, clientAlive = false)
            }
        }
    }

    private var lastActivationReason: Int = ACTIVATION_MANUAL

    /**
     * Initializes the input service, cursor overlay, and input listeners for the specified profile.
     *
     * @param profile The keymap profile to load.
     * @param keymapConfig Configuration settings for keymapping.
     * @param cb Callback for remote service events.
     * @param screenWidth Screen resolution (horizontal).
     * @param screenHeight Screen resolution (vertical).
     * @param displayId Target display ID.
     * @param activationReason Reason for activating or updating the server (e.g. manual vs display change).
     */
    @Throws(RemoteException::class)
    override fun startServer(
        profile: KeymapProfile,
        keymapConfig: KeymapConfig,
        cb: IRemoteServiceCallback,
        screenWidth: Int,
        screenHeight: Int,
        displayId: Int,
        activationReason: Int
    ) {
        enforceCaller()
        if (nativeWaylandFd != null) {
            check(isNativeWaylandCaptureEnabled()) {
                "Native Wayland capture is available only in an opted-in validation build"
            }
            require(!profile.disabled && profile.isNativeWaylandReady()) {
                "Native Wayland capture requires an enabled physical-key profile"
            }
            require(screenWidth > 0 && screenHeight > 0)
            profile.waydroidShortcuts?.applyTo(keymapConfig)
        }
        lastActivationReason = activationReason
        Log.i(TAG, "startServer: requested wayland=$isWaylandClient display=$displayId reason=$activationReason pointerMode=${keymapConfig.pointerMode} thread=${Thread.currentThread().name}")
        XtLogger.info(TAG, "startServer requested wayland=$isWaylandClient display=$displayId reason=$activationReason pointerMode=${keymapConfig.pointerMode}")
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
                activationReason,
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
        activationReason: Int,
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
                    activationReason,
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
            if (usesLegacyWaylandInput) {
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
        if (nativeWaylandFd == null && keymapConfig.pointerMode != KeymapConfig.POINTER_SYSTEM) {
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
            if (waylandCapture != null) {
                newService.mouseEventHandler.canActivateAim = {
                    val available = waylandState and WaylandCaptureNative.RELATIVE_POINTER_AVAILABLE != 0
                    if (!available) Log.w(TAG, "Wayland aim requires relative pointer and pointer constraints")
                    available
                }
                waylandWidth = screenWidth
                waylandHeight = screenHeight
                newService.onWaylandPauseChanged = {
                    if (mInputService.get() === newService) {
                        setWaylandVisible(!newService.stopEvents, newService)
                    }
                }
                setWaylandVisible(true, newService)
            }
            Log.i(TAG, "startServer: Wayland mode; skipping evdev mouse connection")
        }

        // Launch app/game only after this request has actually installed its service.
        if (activationReason != ACTIVATION_DISPLAY_CHANGE && profile.packageName != BuildConfig.APPLICATION_ID && keymapConfig.disableAutoProfiling) {
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

    /**
     * Destroys the remote service instance and stops all active servers.
     */
    override fun destroy() {
        enforceCaller()
        synchronized(mStartRequestLock) {
            destroyRequested = true
            mStartRequestSequence.incrementAndGet()
        }
        if (nativeWaylandFd != null) {
            mHandler.post {
                try {
                    stopServer(true)
                } finally {
                    try {
                        waylandCapture?.close()
                    } finally {
                        waylandCapture = null
                        XtLogger.endSession(SessionStates.STATUS_SERVICE_STOP)
                        System.exit(0)
                    }
                }
            }
        } else if (usesLegacyWaylandInput) {
            val session = mInputService.owner()
            if (session == null) {
                hideOverlayCursor()
                stopServer(true)
                return
            }

            val (service, superseded) = mInputService.claimForShutdown(session)
            if (superseded) {
                XtLogger.info(TAG, "ignored destroy request for a replaced client session")
                return
            }
            hideOverlayCursor()
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

    /**
     * Stops the remote server asynchronously on the main handler.
     */
    override fun stopServer() {
        enforceCaller()
        mHandler.post { stopServer(true) }
    }

    /**
     * Internal implementation to shut down input devices and cleanly terminate server operations.
     *
     * @param exitProcess Whether to exit the hosting process upon stopping.
     * @param clientAlive Whether the client callback Binder is still alive to receive disablePointer.
     */
    private fun stopServer(exitProcess: Boolean, clientAlive: Boolean = true) {
        DeviceTelemetry.stop()
        val service = mInputService.get()
        Log.i(TAG, "stopServer: exitProcess=$exitProcess startedFromShell=$startedFromShell serviceReady=${service != null} mouseOpen=${mouseDevice.isOpen} wayland=$isWaylandClient clientAlive=$clientAlive")
        if (nativeWaylandFd == null && service != null && clientAlive) {
            try {
                service.callback.disablePointer()
            } catch (e: RemoteException) {
                XtLogger.warn(TAG, "Client Binder unavailable during pointer shutdown; continuing cleanup", e)
            }
        }

        if (nativeWaylandFd != null) {
            service?.stopEvents = true
            service?.onWaylandPauseChanged = null
            OrderedCleanup.run(
                "hide Waydroid capture" to { setWaylandVisible(false, service) },
                "hide input cursor" to { service?.hideCursor() },
                "stop input service" to { service?.stop() },
                "clear input service slot" to { mInputService.clear() },
                "disable client pointer" to {
                    if (clientAlive) service?.callback?.disablePointer()
                },
            )
        } else if (!startedFromShell && exitProcess) {
            XtLogger.info(TAG, "server stopped; exiting process")
            // END marker before the existing System.exit; endSession is bounded.
            XtLogger.endSession(SessionStates.STATUS_SERVICE_STOP)
            stopInputReader()
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
        // The native Waydroid daemon exits on destroy. Ordinary stops never close admission.
        // Legacy shell daemons can reopen admission after their non-exit cleanup.
        if (nativeWaylandFd == null) {
            synchronized(mStartRequestLock) { destroyRequested = false }
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

    /**
     * Resumes the keymapping event processing.
     */
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
        if (nativeWaylandFd != null) {
            mHandler.post { mInputService.get()?.reloadKeymap() }
        } else {
            mInputService.get()?.reloadKeymap()
        }
    }

    /**
     * Checks whether the input service is currently active and processing events.
     */
    override fun isActive(): Boolean {
        enforceCaller()
        return mInputService.get() != null
    }

    companion object {
        // Keep the version distinct from APKs predating the Waydroid profile parcel fields.
        internal const val PROTOCOL_VERSION = BuildConfig.VERSION_CODE + 10_000_000
        const val TAG = "xtmapper-server"
        private const val WAYLAND_INPUT_FOCUS = WaylandCaptureNative.MAPPED or
            WaylandCaptureNative.KEYBOARD_FOCUS or WaylandCaptureNative.POINTER_FOCUS

        /**
         * Loads native C++ shared libraries for mouse, touchpad, and keyboard handling.
         */
        fun loadLibraries() {
            System.loadLibrary("mouse_read")
            System.loadLibrary("mouse_cursor")
            System.loadLibrary("touchpad_direct")
            System.loadLibrary("touchpad_relative")
            System.loadLibrary("keyboard_grab")
        }
    }
}
