package xtr.keymapper.dpad

import android.os.Handler
import android.os.SystemClock
import xtr.keymapper.keymap.element.Dpad
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService

class DpadHandler(
    dpad: Dpad,
    private val pointerId: Int,
    // KeyEventHandler stores its Handler as nullable
    private val mHandler: Handler?,
    private val delayMillis: Int
) {

    private val moveUp: DpadEvent
    private val moveDown: DpadEvent
    private val moveLeft: DpadEvent
    private val moveRight: DpadEvent

    private val moveUpLeft: DpadEvent
    private val moveUpRight: DpadEvent
    private val moveDownLeft: DpadEvent
    private val moveDownRight: DpadEvent

    private val tapUp: DpadEvent
    private val tapDown: DpadEvent

    private var KEY_UP = false
    private var KEY_DOWN = false
    private var KEY_LEFT = false
    private var KEY_RIGHT = false
    private val KEY_UP_CODE: String = dpad.keycodes.Up
    private val KEY_DOWN_CODE: String = dpad.keycodes.Down
    private val KEY_LEFT_CODE: String = dpad.keycodes.Left
    private val KEY_RIGHT_CODE: String = dpad.keycodes.Right

    private lateinit var input: IInputInterface
    private var lastEvent: Long = 0

    private class DpadEvent(val x: Float, val y: Float, val action: Int)

    init {
        val radius = dpad.radius
        val xOfCenter = dpad.xOfCenter
        val yOfCenter = dpad.yOfCenter

        moveUp = DpadEvent(xOfCenter, yOfCenter - radius, InputService.MOVE)
        moveDown = DpadEvent(xOfCenter, yOfCenter + radius, InputService.MOVE)
        moveLeft = DpadEvent(xOfCenter - radius, yOfCenter, InputService.MOVE)
        moveRight = DpadEvent(xOfCenter + radius, yOfCenter, InputService.MOVE)

        moveUpLeft = DpadEvent(xOfCenter - radius, yOfCenter - radius, InputService.MOVE)
        moveUpRight = DpadEvent(xOfCenter + radius, yOfCenter - radius, InputService.MOVE)
        moveDownLeft = DpadEvent(xOfCenter - radius, yOfCenter + radius, InputService.MOVE)
        moveDownRight = DpadEvent(xOfCenter + radius, yOfCenter + radius, InputService.MOVE)

        tapUp = DpadEvent(xOfCenter, yOfCenter, InputService.UP)
        tapDown = DpadEvent(xOfCenter, yOfCenter, InputService.DOWN)
    }

    fun setInterface(input: IInputInterface) {
        this.input = input
    }

    fun handleEvent(key: String, action: Int) {
        if (action == InputService.DOWN) {
            sendEventDown(key)
        } else {
            sendEventUp(key)
        }
    }

    private fun sendDpadEvent(event: DpadEvent) {
        val now = SystemClock.uptimeMillis()

        val sendEvent = Runnable {
            input.injectEvent(event.x, event.y, event.action, pointerId)
        }
        if ((now - lastEvent) >= delayMillis) mHandler!!.postDelayed(sendEvent, delayMillis.toLong())
        else mHandler!!.postDelayed(sendEvent, delayMillis * 2L)
        lastEvent = now
    }

    private fun sendEventDown(key: String) {
        if (key == KEY_UP_CODE) {
            KEY_UP = true
            if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT) {
                sendDpadEvent(tapDown) // Send pointer down event only if no other keys are pressed
            }
            // For moving Dpad in 8 directions with 4 keys
            if (KEY_LEFT)
                sendDpadEvent(moveUpLeft) // If left key is already pressed then move dpad to north-west
            else if (KEY_RIGHT)
                sendDpadEvent(moveUpRight) // If right key is already pressed then move dpad to north-east
            else
                sendDpadEvent(moveUp) // If left or right keys are not pressed then move dpad straight up
        } else if (key == KEY_DOWN_CODE) {
            KEY_DOWN = true
            if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapDown)

            if (KEY_LEFT) sendDpadEvent(moveDownLeft)
            else if (KEY_RIGHT) sendDpadEvent(moveDownRight)
            else sendDpadEvent(moveDown)
        } else if (key == KEY_LEFT_CODE) {
            KEY_LEFT = true
            if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapDown)

            if (KEY_UP) sendDpadEvent(moveUpLeft)
            else if (KEY_DOWN) sendDpadEvent(moveDownLeft)
            else sendDpadEvent(moveLeft)
        } else if (key == KEY_RIGHT_CODE) {
            KEY_RIGHT = true
            if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                sendDpadEvent(tapDown)

            if (KEY_UP) sendDpadEvent(moveUpRight)
            else if (KEY_DOWN) sendDpadEvent(moveDownRight)
            else sendDpadEvent(moveRight)
        }
    }

    private fun sendEventUp(key: String) {
        if (key == KEY_UP_CODE) {
            KEY_UP = false
            if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT)
                sendDpadEvent(tapUp)

            if (KEY_LEFT) sendDpadEvent(moveLeft)
            else if (KEY_RIGHT) sendDpadEvent(moveRight)
        } else if (key == KEY_DOWN_CODE) {
            KEY_DOWN = false
            if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapUp)

            if (KEY_LEFT) sendDpadEvent(moveLeft)
            else if (KEY_RIGHT) sendDpadEvent(moveRight)
        } else if (key == KEY_LEFT_CODE) {
            KEY_LEFT = false
            if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapUp)

            if (KEY_UP) sendDpadEvent(moveUp)
            else if (KEY_DOWN) sendDpadEvent(moveDown)
        } else if (key == KEY_RIGHT_CODE) {
            KEY_RIGHT = false
            if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                sendDpadEvent(tapUp)

            if (KEY_UP) sendDpadEvent(moveUp)
            else if (KEY_DOWN) sendDpadEvent(moveDown)
        }
    }
}
