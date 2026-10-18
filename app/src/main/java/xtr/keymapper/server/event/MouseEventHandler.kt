package xtr.keymapper.server.event

import android.os.RemoteException
import android.util.Log
import xtr.keymapper.InputEventCodes.BTN_EXTRA
import xtr.keymapper.InputEventCodes.BTN_MIDDLE
import xtr.keymapper.InputEventCodes.BTN_MOUSE
import xtr.keymapper.InputEventCodes.BTN_RIGHT
import xtr.keymapper.InputEventCodes.BTN_SIDE
import xtr.keymapper.InputEventCodes.REL_WHEEL
import xtr.keymapper.InputEventCodes.REL_X
import xtr.keymapper.InputEventCodes.REL_Y
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.element.Key
import xtr.keymapper.mouse.MouseAimHandler
import xtr.keymapper.mouse.MousePinchZoom
import xtr.keymapper.mouse.MouseWalkHandler
import xtr.keymapper.mouse.MouseWheelZoom
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.RemoteService
import xtr.keymapper.server.pid.PointerId
import java.util.Objects

class MouseEventHandler(private val mInput: IInputInterface) {

    /* SoufianoDev:
     * Concurrency Design – Cross-Thread Visibility
     * =============================================
     * The native input thread reads mouse state while the main thread updates it.
     * Without proper memory visibility, the native thread may see stale values,
     * leading to missed clicks, stuck pointers, or aim mode not being activated.
     *
     * The following fields are declared volatile to guarantee visibility:
     *   - pointer_down: Ensures the native thread sees the latest press/release.
     *   - mouseAimActive: Toggles aim mode; must be visible to the native thread.
     *   - mouseAimOrCameraHandler: The handler reference assigned before the flag.
     *     Without volatile, the compiler could reorder writes, causing the native
     *     thread to see mouseAimActive == true but a null handler → crash.
     *
     * Additionally, triggerMouseAimOrCamera() uses a synchronized block to make
     * the assignment of mouseAimOrCameraHandler and the flag flip atomic.
     */
    @Volatile
    private var pointer_down = false
    @Volatile
    @JvmField
    var mouseAimActive = false
    @Volatile
    private var mouseAimOrCameraHandler: MouseAimHandler? = null

    internal var sensitivity = 0f
    internal var scroll_speed_multiplier = 0f
    private var pinchZoom: MousePinchZoom? = null
    private var scrollZoomHandler: MouseWheelZoom? = null
    private var mouseAimHandler: MouseAimHandler? = null
    private var mouseCameraHandler: MouseAimHandler? = null
    private var rightClick: Key? = null
    internal var x1 = 100
    internal var y1 = 100
    internal var width = 0
    internal var height = 0

    private var mouseWalkActive = false
    private var mouseWalkHandler: MouseWalkHandler? = null

    fun triggerMouseAim(): Boolean {
        return triggerMouseAimOrCamera(mouseAimHandler)
    }

    fun triggerCamera() {
        triggerMouseAimOrCamera(mouseCameraHandler)
    }

    private fun triggerMouseAimOrCamera(instance: MouseAimHandler?): Boolean {
        if (instance == null) return false

        synchronized(this) {
            mouseAimOrCameraHandler = instance
            mouseAimActive = !mouseAimActive

            if (mouseAimActive) {
                stopMouseWalk()
                instance.resetPointer()
                // Notifying user that shooting mode was activated
                try {
                    mInput.callback.alertMouseAimActivated()
                } catch (e: RemoteException) {
                    Log.e(RemoteService.TAG, Objects.requireNonNull(e.message), e)
                }
                mInput.hideCursor()
            } else {
                instance.stop()
                mInput.showCursor()
            }
            return true
        }
    }

    fun init() {
        init(width, height)
    }

    fun init(width: Int, height: Int) {
        this.width = width
        this.height = height

        val profile: KeymapProfile = mInput.keymapProfile
        if (profile.mouseAimConfig != null)
            mouseAimHandler = MouseAimHandler(profile.mouseAimConfig!!)
        if (profile.camera != null)
            mouseCameraHandler = MouseAimHandler(profile.camera!!)
        if (profile.mouseWalk != null)
            mouseWalkHandler = MouseWalkHandler(profile.mouseWalk!!)

        this.rightClick = profile.rightClick

        mouseAimHandler?.let {
            it.setInterface(mInput)
            it.setDimensions(width, height)
        }
        mouseCameraHandler?.let {
            it.setInterface(mInput)
            it.setDimensions(width, height)
        }
        mouseWalkHandler?.let {
            it.setInterface(mInput)
            it.setDimensions(width, height)
        }

        val keymapConfig = mInput.keymapConfig
        if (keymapConfig.ctrlMouseWheelZoom)
            scrollZoomHandler = MouseWheelZoom(mInput)

        sensitivity = keymapConfig.mouseSensitivity!!
        scroll_speed_multiplier = keymapConfig.scrollSpeed!!
    }

    // Consolidated coordinate update – single injection per movement
    private fun updatePositionAndInject(dx: Int, dy: Int) {
        if (dx != 0) {
            x1 += dx
            x1 = maxOf(0, minOf(width, x1))
        }
        if (dy != 0) {
            y1 += dy
            y1 = maxOf(0, minOf(height, y1))
        }

        if (pointer_down) {
            mInput.injectEvent(x1.toFloat(), y1.toFloat(), InputService.MOVE, pointerId)
        } else {
            mInput.injectHoverEvent(x1.toFloat(), y1.toFloat(), pointerId)
        }

        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler!!.onCursorPosition(x1, y1)
        }

        mInput.moveCursorX(x1)
        mInput.moveCursorY(y1)
    }

    private fun startMouseWalk() {
        if (mouseAimOrCameraHandler != null && mouseAimActive) {
            triggerMouseAimOrCamera(mouseAimOrCameraHandler)
        }
        // Makes sure that pointer is up before starting
        mouseWalkHandler?.let {
            it.resetPointer()
            mouseWalkActive = true
        }
    }

    private fun stopMouseWalk() {
        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler!!.stop()
            mouseWalkActive = false
        }
    }

    private fun handleRightClick(value: Int): Boolean {
        if (value == 1) {
            if (mouseWalkHandler != null) {
                if (mouseWalkActive) stopMouseWalk()
                else startMouseWalk()
                return true
            } else if (mInput.keymapConfig.rightClickMouseAim)
                return triggerMouseAim()
            else if (Objects.equals(mInput.keymapConfig.mouseAimShortcutKey, "KEY_RMB"))
                return triggerMouseAim()
        } else if (rightClick != null) {
            mInput.injectEvent(rightClick!!.x, rightClick!!.y, value, pointerId)
            return true
        }
        return false
    }

    fun handleEvent(code: Int, value: Int) {
        if (mouseAimOrCameraHandler != null && mouseAimActive) {
            mouseAimOrCameraHandler!!.handleEvent(code, value) { c, v -> handleMouseEvent(c, v) }
        } else {
            handleMouseEvent(code, value)
        }
    }

    private fun handleMouseEvent(code: Int, value: Int) {
        val keymapConfig = mInput.keymapConfig
        if (mInput.keyEventHandler.ctrlKeyPressed && pointer_down
            && keymapConfig.ctrlDragMouseGesture
        ) {
            if (pinchZoom != null) pointer_down = pinchZoom!!.handleEvent(code, value)
            return
        }

        var dx = 0
        var dy = 0

        when (code) {
            REL_X -> dx = (value * sensitivity).toInt()
            REL_Y -> dy = (value * sensitivity).toInt()
            BTN_MOUSE -> {
                pointer_down = value == 1
                if (mInput.keyEventHandler.ctrlKeyPressed && keymapConfig.ctrlDragMouseGesture) {
                    pinchZoom = MousePinchZoom(mInput, x1.toFloat(), y1.toFloat())
                    pinchZoom!!.handleEvent(code, value)
                } else {
                    mInput.injectEvent(x1.toFloat(), y1.toFloat(), value, pointerId)
                }
            }
            BTN_RIGHT -> if (!handleRightClick(value)) {
                mInput.injectRightClickEvent(x1.toFloat(), y1.toFloat(), pointerId, value == 1)
            }
            BTN_EXTRA, BTN_SIDE, BTN_MIDDLE -> if (value == 1 && Objects.equals(mInput.keymapConfig.mouseAimShortcutKey, "KEY_MMB")) {
                triggerMouseAim()
            } else if (value == 1 && Objects.equals(mInput.keymapProfile.camera!!.triggerKeyCode, "KEY_MMB")) {
                triggerCamera()
            } else {
                mInput.injectMiddleClickEvent(x1.toFloat(), y1.toFloat(), pointerId, value == 1)
            }
            REL_WHEEL -> if (scrollZoomHandler != null && mInput.keyEventHandler.ctrlKeyPressed
                && keymapConfig.ctrlMouseWheelZoom
            ) {
                scrollZoomHandler!!.onScrollEvent(value, x1, y1)
            } else {
                var scrollDelta = (value * scroll_speed_multiplier.toInt())
                scrollDelta = maxOf(-32, minOf(32, scrollDelta))
                mInput.injectScroll(x1.toFloat(), y1.toFloat(), scrollDelta.toFloat())
            }
        }

        if (dx != 0 || dy != 0) {
            updatePositionAndInject(dx, dy)
        }
    }

    fun evAbsY(y: Int) {
        y1 = maxOf(0, minOf(height, y))
        if (pointer_down) {
            mInput.injectEvent(x1.toFloat(), y1.toFloat(), InputService.MOVE, pointerId)
        } else {
            mInput.injectHoverEvent(x1.toFloat(), y1.toFloat(), pointerId)
        }
        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler!!.onCursorPosition(x1, y1)
        }
        mInput.moveCursorY(y1)
    }

    fun evAbsX(x: Int) {
        x1 = maxOf(0, minOf(width, x))
        if (pointer_down) {
            mInput.injectEvent(x1.toFloat(), y1.toFloat(), InputService.MOVE, pointerId)
        } else {
            mInput.injectHoverEvent(x1.toFloat(), y1.toFloat(), pointerId)
        }
        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler!!.onCursorPosition(x1, y1)
        }
        mInput.moveCursorX(x1)
    }

    fun stop() {
        scrollZoomHandler = null
        pinchZoom = null
        mouseAimHandler = null
        mouseCameraHandler = null
        mouseWalkHandler = null
    }

    companion object {
        @JvmField
        val pointerId: Int = PointerId.pid1.id
    }
}
