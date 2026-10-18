package xtr.keymapper.swipekey

import android.os.Handler
import xtr.keymapper.keymap.element.SwipeKey
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.event.KeyEventHandler.KeyEvent
import xtr.keymapper.server.pid.PidProvider

class SwipeKeyHandler(key: SwipeKey) {

    private val swipeEvent1: SwipeEvent
    private val swipeEvent2: SwipeEvent
    private val keycode1: String
    private val keycode2: String

    init {
        keycode1 = "KEY_" + key.key1.code
        keycode2 = "KEY_" + key.key2.code
        val midpointX = (key.key1.x + key.key2.x) / 2
        val midpointY = (key.key1.y + key.key2.y) / 2
        swipeEvent1 = SwipeEvent(midpointX, midpointY, key.key1.x, key.key1.y)
        swipeEvent2 = SwipeEvent(midpointX, midpointY, key.key2.x, key.key2.y)
    }

    private class SwipeEvent(
        val startX: Float,
        val startY: Float,
        val stopX: Float,
        val stopY: Float
    )

    fun handleEvent(
        event: KeyEvent,
        service: IInputInterface,
        pidProvider: PidProvider,
        handler: Handler?,
        swipeDelayMs: Int
    ) {
        val swipeEvent = when (event.code) {
            keycode1 -> swipeEvent1
            keycode2 -> swipeEvent2
            else -> return
        }
        val pid = pidProvider.getPid(event.code)

        service.injectEvent(swipeEvent.startX, swipeEvent.startY, event.action, pid)

        if (event.action == InputService.DOWN) handler?.postDelayed({
            service.injectEvent(swipeEvent.stopX, swipeEvent.stopY, InputService.MOVE, pid)
        }, swipeDelayMs.toLong())
    }
}
