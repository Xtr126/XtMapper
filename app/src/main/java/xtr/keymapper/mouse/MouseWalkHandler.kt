package xtr.keymapper.mouse

import xtr.keymapper.keymap.element.MouseWalk
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.pid.PointerId
import kotlin.math.hypot

class MouseWalkHandler(private val config: MouseWalk) {
    private var currentX: Float = 0F
    private var currentY: Float = 0F
    private var centerX: Float = 0F
    private var centerY: Float = 0F
    private var service: IInputInterface? = null
    private val pointerIdAim = PointerId.pid2.id

    fun setInterface(input: IInputInterface) {
        this.service = input
    }

    fun setDimensions(width: Int, height: Int) {
        centerX = width.toFloat() / 2
        centerY = height.toFloat() / 2
    }

    fun resetPointer() {
        service!!.injectEvent(currentX, currentY, InputService.UP, pointerIdAim)
        config.apply {
            currentY = y
            currentX = x
        }
        service!!.injectEvent(currentX, currentY, InputService.DOWN, pointerIdAim)
    }

    fun onCursorPosition(x: Int, y: Int) {
        val cRadius = hypot(centerX - x, centerY - y)
        currentX = ((x - centerX) * config.radius) / cRadius
        currentX += config.x
        currentY = ((y - centerY) * config.radius) / cRadius
        currentY += config.y
        service!!.injectEvent(currentX, currentY, InputService.MOVE, pointerIdAim)
    }


    fun stop() {
        service!!.injectEvent(currentX, currentY, InputService.UP, pointerIdAim)
    }
}
