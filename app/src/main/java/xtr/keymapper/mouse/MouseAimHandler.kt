package xtr.keymapper.mouse

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import xtr.keymapper.InputEventCodes.BTN_EXTRA
import xtr.keymapper.InputEventCodes.BTN_MIDDLE
import xtr.keymapper.InputEventCodes.BTN_MOUSE
import xtr.keymapper.InputEventCodes.BTN_RIGHT
import xtr.keymapper.InputEventCodes.BTN_SIDE
import xtr.keymapper.InputEventCodes.REL_X
import xtr.keymapper.InputEventCodes.REL_Y
import xtr.keymapper.keymap.element.Camera
import xtr.keymapper.keymap.element.MouseAimConfig
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.pid.PointerId
import kotlin.math.hypot
import kotlin.math.sqrt

class MouseAimHandler(private val config: MouseAimConfig) {
    private var currentX = config.xCenter
    private var currentY = config.yCenter
    private val area = RectF()
    private lateinit var service: IInputInterface
    private val pointerIdMouse = PointerId.pid1.id
    private val pointerIdAim = PointerId.pid2.id
    private val mHandler = Handler(Looper.getMainLooper())

    constructor(camera: Camera) : this(
        MouseAimConfig(
            camera.x, camera.y, 0F, 0F, 0F, 0F, true,
            camera.xSensitivity, camera.ySensitivity, false
        )
    )

    fun setInterface(input: IInputInterface) {
        service = input
    }

    fun setDimensions(width: Int, height: Int) {
        if (config.width == 0F || config.height == 0F) {
            // Reset pointer if jumping out of screenspace
            area.left = 0F; area.top = 0F
            area.right = width.toFloat()
            area.bottom = height.toFloat()
        } else {
            // An area around the center point
            area.left = currentX - config.width
            area.right = currentX + config.width
            area.top = currentY - config.height
            area.bottom = currentY + config.height
        }
    }

    fun resetPointer() {
        service.injectEvent(currentX, currentY, InputService.UP, pointerIdAim)
        mHandler.postDelayed({
            currentY = config.yCenter
            currentX = config.xCenter
            service.injectEvent(currentX, currentY, InputService.DOWN, pointerIdAim)
        }, service.keymapConfig.swipeDelayMs.toLong())
    }

    fun handleEvent(code: Int, value: Int, listener: OnButtonClickListener) {
        when (code) {
            REL_X -> {
                currentX += calculateScaledX(value)
                if (config.limitedBounds && (currentX > area.right || currentX < area.left))
                    resetPointer()
                service.injectEvent(currentX, currentY, InputService.MOVE, pointerIdAim)
            }
            REL_Y -> {
                currentY += calculateScaledY(value)
                if (config.limitedBounds && (currentY > area.bottom || currentY < area.top))
                    resetPointer()
                service.injectEvent(currentX, currentY, InputService.MOVE, pointerIdAim)
            }

            BTN_MOUSE ->
                service.injectEvent(config.xleftClick, config.yleftClick, value, pointerIdMouse)

            BTN_SIDE, BTN_MIDDLE, BTN_EXTRA, BTN_RIGHT ->
                listener.onButtonClick(code, value)
        }
    }

    private fun calculateScaledX(value: Int): Float {
        if (config.applyNonLinearScaling) {
            val dx = (config.xCenter - currentX).toDouble()
            val dy = (config.yCenter - currentY).toDouble()
            val distance = hypot(dx, dy)

            val maxWidth = (area.right - area.left).toDouble()
            val minDistanceToApplyScaling = maxWidth / 20
            return if (distance > minDistanceToApplyScaling)
                config.xSensitivity * value * sqrt(minDistanceToApplyScaling / distance).toFloat()
            else value * config.xSensitivity
        }
        return if (config.xSensitivity != 1.0F) value * config.xSensitivity else value.toFloat()
    }

    private fun calculateScaledY(value: Int): Float =
        if (config.ySensitivity != 1.0F) value * config.ySensitivity else value.toFloat()

    fun stop() {
        service.injectEvent(currentX, currentY, InputService.UP, pointerIdAim)
    }

    fun interface OnButtonClickListener {
        fun onButtonClick(code: Int, value: Int)
    }
}
