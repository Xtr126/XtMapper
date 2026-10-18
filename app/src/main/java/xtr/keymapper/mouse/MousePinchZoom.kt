package xtr.keymapper.mouse

import xtr.keymapper.InputEventCodes.BTN_MOUSE
import xtr.keymapper.InputEventCodes.REL_X
import xtr.keymapper.InputEventCodes.REL_Y
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.pid.PointerId

class MousePinchZoom(private val service: IInputInterface, initX: Float, initY: Float) {
    private var currentX1 = initX + pixels
    private var currentY1 = initY + pixels
    private var currentX2 = initX - pixels
    private var currentY2 = initY - pixels
    private val centerX = initX
    private val centerY = initY

    private fun initPointers() {
        service.injectEvent(currentX1, currentY1, InputService.DOWN, pointerId1)
        service.injectEvent(currentX2, currentY2, InputService.DOWN, pointerId2)
    }

    private fun releasePointers() {
        service.injectEvent(currentX1, currentY1, InputService.UP, pointerId1)
        service.injectEvent(currentX2, currentY2, InputService.UP, pointerId2)
    }

    /*
     * Move position of pointers away from center
     * To make space for performing zoom out gesture
     */
    private fun moveAwayPointers() {
        releasePointers()
        currentX1 += 100; currentX2 -= 100
        currentY1 += 100; currentY2 -= 100
        initPointers()
    }

    fun handleEvent(code: Int, value: Int): Boolean {
        when (code) {
            REL_X -> {
                currentX1 += value
                currentX2 -= value
                // If it passed through the center in opposite direction
                if (centerX > currentX1) moveAwayPointers()

                service.injectEvent(currentX1, currentY1, InputService.MOVE, pointerId1)
                service.injectEvent(currentX2, currentY2, InputService.MOVE, pointerId2)
            }
            REL_Y -> {
                currentY1 += value
                currentY2 -= value
                if (centerY > currentY1) moveAwayPointers()

                service.injectEvent(currentX1, currentY1, InputService.MOVE, pointerId1)
                service.injectEvent(currentX2, currentY2, InputService.MOVE, pointerId2)
            }
            BTN_MOUSE -> {
                service.injectEvent(currentX1, currentY1, value, pointerId1)
                service.injectEvent(currentX2, currentY2, value, pointerId2)
                if (value == InputService.UP) return false
            }
        }
        return true
    }

    companion object {
        val pointerId1 = PointerId.pid1.id
        val pointerId2 = PointerId.pid2.id
        // Shift initial position of two pointers by 50 pixels
        private const val pixels = 50
    }
}
