package xtr.keymapper.server

import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.View
import xtr.keymapper.InputEventCodes.BTN_MIDDLE
import xtr.keymapper.InputEventCodes.BTN_MOUSE
import xtr.keymapper.InputEventCodes.BTN_RIGHT
import xtr.keymapper.InputEventCodes.REL_WHEEL
import xtr.keymapper.InputEventCodes.REL_X
import xtr.keymapper.InputEventCodes.REL_Y
import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.server.event.KeyEventHandler
import xtr.keymapper.server.event.MouseEventHandler

class InputService @Throws(RemoteException::class) constructor(
    profile: KeymapProfile,
    keymapConfig: KeymapConfig,
    private val mCallback: IRemoteServiceCallback,
    screenWidth: Int,
    screenHeight: Int,
    private val cursorView: View?,
    private val isWaylandClient: Boolean,
    displayId: Int
) : IInputInterface {

    override var keymapConfig: KeymapConfig = keymapConfig
    override var keymapProfile: KeymapProfile = profile
    override val mouseEventHandler: MouseEventHandler
    override val keyEventHandler: KeyEventHandler
    override val callback: IRemoteServiceCallback = mCallback
    private val input: Input
    private val touchpadInputMode: Int
    private val currentPointerMode: Int

    private val mHandler = Handler(Looper.getMainLooper())
    private var supportsAbsEvents = false

    internal var stopEvents = false
    val touchJitter = TouchJitter(keymapConfig.touchJitter, keymapConfig.touchJitterSpread)
    var keyListener: ((String) -> Unit)? = null
    var isKeyboardGrabbed: Boolean = false
        private set
    override var isKeyboardBlocked: Boolean = false
        private set

    init {
        profile.scale(screenWidth.toFloat(), screenHeight.toFloat())
        this.currentPointerMode = keymapConfig.pointerMode
        if (currentPointerMode != KeymapConfig.POINTER_OVERLAY) {
            initMouseCursor(screenWidth, screenHeight)
            // Reduce visibility of system pointer
            cursorSetX(0)
            cursorSetY(0)
        }
        if (currentPointerMode != KeymapConfig.POINTER_SYSTEM) showCursor()

        // Disable touchpad features for wayland client
        this.touchpadInputMode = if (isWaylandClient) KeymapConfig.TOUCHPAD_DISABLED else keymapConfig.touchpadInputMode

        if (touchpadInputMode == KeymapConfig.TOUCHPAD_DIRECT)
            startTouchpadDirect()
        else if (touchpadInputMode == KeymapConfig.TOUCHPAD_RELATIVE)
            startTouchpadRelative()

        mouseEventHandler = MouseEventHandler(this)
        mouseEventHandler.init(screenWidth, screenHeight)

        keyEventHandler = KeyEventHandler(this)
        keyEventHandler.init()
        input = Input(displayId)
        touchJitter.setScreenDimensions(screenWidth, screenHeight)

        if (keymapConfig.isRootMode && keymapConfig.blockKeyboardInput && !isWaylandClient) {
            try {
                isKeyboardGrabbed = startKeyboardGrab()
                isKeyboardBlocked = isKeyboardGrabbed
            } catch (e: Exception) {
                Log.e(RemoteService.TAG, "Failed to start keyboard grab", e)
            }
        }
    }

    override fun injectEvent(x: Float, y: Float, action: Int, pointerId: Int) {
        val target = touchJitter.wrapCoordinates(x, y, action, pointerId)
        when (action) {
            UP -> input.injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f, target.x, target.y)
            DOWN -> input.injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f, target.x, target.y)
            MOVE -> input.injectTouch(MotionEvent.ACTION_MOVE, pointerId, 0.0f, target.x, target.y)
        }
    }

    override fun injectHoverEvent(x: Float, y: Float, pointerId: Int) {
        if (input.noPointersDown() && currentPointerMode == KeymapConfig.POINTER_OVERLAY)
            input.injectTouch(MotionEvent.ACTION_HOVER_MOVE, pointerId, 1.0f, x, y)
    }

    override fun injectRightClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {
        input.injectMouseClick(pointerId.toLong(), x, y, pressed, MotionEvent.BUTTON_SECONDARY)
    }

    override fun injectMiddleClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {
        input.injectMouseClick(pointerId.toLong(), x, y, pressed, MotionEvent.BUTTON_TERTIARY)
    }

    override fun injectScroll(x: Float, y: Float, value: Float) {
        input.onScrollEvent(x, y, value)
    }

    override fun pauseResumeKeymap() {
        stopEvents = !stopEvents
        if (!isWaylandClient) {
            setMouseLock(!stopEvents)
            if (isKeyboardGrabbed) {
                setKeyboardGrabLock(!stopEvents && isKeyboardBlocked)
            }
        }
    }

    override fun toggleKeyboardBlock() {
        if (!keymapConfig.isRootMode || isWaylandClient) return
        if (!isKeyboardGrabbed) {
            try {
                isKeyboardGrabbed = startKeyboardGrab()
                if (!isKeyboardGrabbed) {
                    Log.w(RemoteService.TAG, "startKeyboardGrab returned false, no keyboard device grabbed")
                    return
                }
                isKeyboardBlocked = true
            } catch (e: Exception) {
                Log.e(RemoteService.TAG, "Failed to start keyboard grab", e)
                return
            }
        } else {
            isKeyboardBlocked = !isKeyboardBlocked
            setKeyboardGrabLock(isKeyboardBlocked && !stopEvents)
        }
        try {
            mCallback.alertKeyboardBlocked(isKeyboardBlocked && !stopEvents)
        } catch (e: RemoteException) {
            Log.e(RemoteService.TAG, "Failed to send alertKeyboardBlocked", e)
        }
    }

    override fun moveCursorX(x: Int) {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.x = x.toFloat() }
        } else {
            try {
                mCallback.setCursorX(x)
            } catch (ignored: RemoteException) {
            }
        }
        if (currentPointerMode != KeymapConfig.POINTER_OVERLAY) {
            // To avoid conflict with touch input when moving virtual pointer
            if (input.noPointersDown()) cursorSetX(x)
        }
    }

    override fun moveCursorY(y: Int) {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.y = y.toFloat() }
        } else {
            try {
                mCallback.setCursorY(y)
            } catch (ignored: RemoteException) {
            }
        }
        if (currentPointerMode != KeymapConfig.POINTER_OVERLAY) {
            // To avoid conflict with touch input when moving virtual pointer
            if (input.noPointersDown()) cursorSetY(y)
        }
    }

    override fun hideCursor() {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.visibility = View.GONE }
        } else {
            try {
                mCallback.disablePointer()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    override fun showCursor() {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.visibility = View.VISIBLE }
        } else {
            try {
                mCallback.enablePointer()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    fun reloadKeymap() {
        try {
            this.keymapProfile = mCallback.requestKeymapProfile()
            this.keymapConfig = mCallback.requestKeymapConfig()
            this.stop()
            keyEventHandler.init()
            mouseEventHandler.init()
            if (keymapConfig.isRootMode && keymapConfig.blockKeyboardInput && !isWaylandClient) {
                try {
                    isKeyboardGrabbed = startKeyboardGrab()
                    isKeyboardBlocked = isKeyboardGrabbed
                } catch (e: Exception) {
                    Log.e(RemoteService.TAG, "Failed to start keyboard grab", e)
                }
            }
        } catch (e: Exception) {
            Log.e(RemoteService.TAG, e.message, e)
        }
    }

    fun stop() {
        if (isKeyboardGrabbed) {
            try {
                stopKeyboardGrab()
            } catch (e: Exception) {
                Log.e(RemoteService.TAG, "Failed to stop keyboard grab", e)
            }
            isKeyboardGrabbed = false
            isKeyboardBlocked = false
        }
        keyEventHandler.stop()
        mouseEventHandler.stop()
    }

    fun stopTouchpad() {
        if (touchpadInputMode == KeymapConfig.TOUCHPAD_DIRECT)
            stopTouchpadDirect()
        else if (touchpadInputMode == KeymapConfig.TOUCHPAD_RELATIVE)
            stopTouchpadRelative()
    }

    external fun openDevice(device: String): Int
    external fun stopMouse()

    // mouse cursor created with uinput in mouse_cursor.cpp
    external fun cursorSetX(x: Int)
    external fun cursorSetY(y: Int)
    private external fun initMouseCursor(width: Int, height: Int): Int
    external fun destroyUinputDev()

    external fun setMouseLock(lock: Boolean)

    // keyboard_grab.cpp
    external fun startKeyboardGrab(): Boolean
    external fun stopKeyboardGrab()
    external fun setKeyboardGrabLock(lock: Boolean)
    external fun grabKeyboardDevice(device: String): Boolean

    // touchpad_direct.cpp
    private external fun startTouchpadDirect()
    external fun stopTouchpadDirect()

    private external fun startTouchpadRelative()
    external fun stopTouchpadRelative()

    /*
     * Called from native code to send mouse event to client
     */
    fun sendMouseEvent(code: Int, value: Int) {
        if (!stopEvents) mouseEventHandler.handleEvent(code, value)
    }

    /*
     * Called from native code when keyboard is grabbed
     */
    fun sendKeyboardEvent(codeName: String, action: Int) {
        val actionStr = if (action == DOWN) "DOWN" else "UP"
        val line = "EV_KEY $codeName $actionStr"
        try {
            if (shouldHandleMappedKeyboardEvent(stopEvents, isKeyboardBlocked)) {
                keyEventHandler.handleEvent(line)
            } else {
                keyEventHandler.handleKeyboardShortcutEvent(line)
            }
        } catch (e: Exception) {
            Log.e(RemoteService.TAG, "Error handling keyboard event", e)
        }
        try {
            keyListener?.invoke(line)
        } catch (e: Exception) {
            Log.e(RemoteService.TAG, "Error invoking keyListener", e)
        }
    }

    fun onWaylandMouseEvent(line: String) {
        val input_event = line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val value = input_event[3].toInt()
        when (input_event[2]) {
            "ABS_X" -> {
                supportsAbsEvents = true
                if (!mouseEventHandler.mouseAimActive)
                    mouseEventHandler.evAbsX(value)
            }
            "ABS_Y" -> if (!mouseEventHandler.mouseAimActive) mouseEventHandler.evAbsY(value)
            "REL_WHEEL" -> mouseEventHandler.handleEvent(REL_WHEEL, value)
            "BTN_LEFT" -> mouseEventHandler.handleEvent(BTN_MOUSE, value)
            "BTN_RIGHT" -> mouseEventHandler.handleEvent(BTN_RIGHT, value)
            "BTN_MIDDLE" -> mouseEventHandler.handleEvent(BTN_MIDDLE, value)
            "REL_X" -> if (mouseEventHandler.mouseAimActive || !supportsAbsEvents)
                mouseEventHandler.handleEvent(REL_X, value)
            "REL_Y" -> if (mouseEventHandler.mouseAimActive || !supportsAbsEvents)
                mouseEventHandler.handleEvent(REL_Y, value)
        }
    }

    companion object {
        const val UP = 0
        const val DOWN = 1
        const val MOVE = 2
    }
}

internal fun shouldHandleMappedKeyboardEvent(stopEvents: Boolean, isKeyboardBlocked: Boolean): Boolean =
    !stopEvents && isKeyboardBlocked
