package xtr.keymapper.shiftkey

import android.os.Handler
import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.event.KeyEventHandler.KeyEvent
import xtr.keymapper.server.pid.PidProvider

class ShiftKeyHandler(key: ShiftKey) {

    private val keycode1: String = "KEY_" + (key.key1.code ?: "").uppercase()
    private val keycode2: String = "KEY_" + (key.key2.code ?: "").uppercase()
    private val key1X: Float = key.key1.x
    private val key1Y: Float = key.key1.y
    private val key2X: Float = key.key2.x
    private val key2Y: Float = key.key2.y
    private val shiftId: String = "SHIFT_" + key.key1.code + "_" + key.key2.code
    val keyDurationMs: Int = key.durationMs

    private var isKey1Down = false
    private var isKey2Down = false
    private var activeKey = 0 // 0 = none, 1 = at key1, 2 = at key2
    private var assignedPid = -1

    var currentX = key1X
        private set
    var currentY = key1Y
        private set
    private var transitionGeneration = 0

    fun handleEvent(
        event: KeyEvent,
        service: IInputInterface,
        pidProvider: PidProvider,
        handler: Handler? = null,
        defaultDurationMs: Int = 100,
        scheduler: ((delayMs: Long, action: () -> Unit) -> Unit)? = null
    ) {
        val eventCode = event.code.uppercase()
        if (eventCode != keycode1 && eventCode != keycode2) return

        val duration = if (keyDurationMs >= 0) keyDurationMs else defaultDurationMs

        if (eventCode == keycode1) {
            if (event.action == InputService.DOWN) {
                if (isKey1Down) return
                isKey1Down = true
                if (!isKey2Down) {
                    // Fresh press on Key 1
                    transitionGeneration++
                    assignedPid = pidProvider.getPid(shiftId)
                    currentX = key1X
                    currentY = key1Y
                    activeKey = 1
                    service.injectEvent(key1X, key1Y, InputService.DOWN, assignedPid)
                } else if (activeKey == 2) {
                    // Shift from Key 2 -> Key 1 and hold
                    activeKey = 1
                    startTransition(key1X, key1Y, duration, service, handler, scheduler)
                }
            } else if (event.action == InputService.UP) {
                if (!isKey1Down) return
                isKey1Down = false
                if (isKey2Down) {
                    // Key 1 released, but Key 2 still held down -> shift back to Key 2 and hold
                    if (activeKey == 1) {
                        activeKey = 2
                        startTransition(key2X, key2Y, duration, service, handler, scheduler)
                    }
                } else {
                    // Both keys released -> lift finger
                    transitionGeneration++
                    service.injectEvent(currentX, currentY, InputService.UP, assignedPid)
                    pidProvider.releasePidFor(shiftId)
                    activeKey = 0
                    assignedPid = -1
                }
            }
        } else if (eventCode == keycode2) {
            if (event.action == InputService.DOWN) {
                if (isKey2Down) return
                isKey2Down = true
                if (!isKey1Down) {
                    // Fresh press on Key 2
                    transitionGeneration++
                    assignedPid = pidProvider.getPid(shiftId)
                    currentX = key2X
                    currentY = key2Y
                    activeKey = 2
                    service.injectEvent(key2X, key2Y, InputService.DOWN, assignedPid)
                } else if (activeKey == 1) {
                    // Shift from Key 1 -> Key 2 and hold
                    activeKey = 2
                    startTransition(key2X, key2Y, duration, service, handler, scheduler)
                }
            } else if (event.action == InputService.UP) {
                if (!isKey2Down) return
                isKey2Down = false
                if (isKey1Down) {
                    // Key 2 released, but Key 1 still held down -> shift back to Key 1 and hold
                    if (activeKey == 2) {
                        activeKey = 1
                        startTransition(key1X, key1Y, duration, service, handler, scheduler)
                    }
                } else {
                    // Both keys released -> lift finger
                    transitionGeneration++
                    service.injectEvent(currentX, currentY, InputService.UP, assignedPid)
                    pidProvider.releasePidFor(shiftId)
                    activeKey = 0
                    assignedPid = -1
                }
            }
        }
    }

    private fun startTransition(
        targetX: Float,
        targetY: Float,
        duration: Int,
        service: IInputInterface,
        handler: Handler?,
        scheduler: ((delayMs: Long, action: () -> Unit) -> Unit)?
    ) {
        val gen = ++transitionGeneration
        val pid = assignedPid
        if (pid == -1) return

        val startX = currentX
        val startY = currentY

        if (duration <= 0 || (handler == null && scheduler == null)) {
            currentX = targetX
            currentY = targetY
            service.injectEvent(targetX, targetY, InputService.MOVE, pid)
            return
        }

        val stepIntervalMs = 12
        val totalSteps = maxOf(2, duration / stepIntervalMs)

        val schedulePost: (Long, () -> Unit) -> Unit = scheduler ?: { delay, action ->
            handler?.postDelayed(action, delay)
        }

        for (i in 1..totalSteps) {
            val delay = (i.toLong() * duration) / totalSteps
            val progress = i.toFloat() / totalSteps.toFloat()
            val stepX = startX + (targetX - startX) * progress
            val stepY = startY + (targetY - startY) * progress

            schedulePost(delay) {
                if (transitionGeneration == gen && assignedPid == pid) {
                    currentX = stepX
                    currentY = stepY
                    service.injectEvent(stepX, stepY, InputService.MOVE, pid)
                }
            }
        }
    }
}
