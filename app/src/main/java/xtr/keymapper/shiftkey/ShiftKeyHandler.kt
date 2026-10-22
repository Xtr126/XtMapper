package xtr.keymapper.shiftkey

import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.event.KeyEventHandler.KeyEvent
import xtr.keymapper.server.pid.PidProvider

class ShiftKeyHandler(key: ShiftKey) {

    private val keycode1: String = "KEY_" + key.key1.code
    private val keycode2: String = "KEY_" + key.key2.code
    private val key1X: Float = key.key1.x
    private val key1Y: Float = key.key1.y
    private val key2X: Float = key.key2.x
    private val key2Y: Float = key.key2.y
    private val shiftId: String = "SHIFT_" + key.key1.code + "_" + key.key2.code

    private var isKey1Down = false
    private var isKey2Down = false
    private var activeKey = 0 // 0 = none, 1 = at key1, 2 = at key2
    private var assignedPid = -1

    fun handleEvent(
        event: KeyEvent,
        service: IInputInterface,
        pidProvider: PidProvider
    ) {
        if (event.code != keycode1 && event.code != keycode2) return

        if (event.code == keycode1) {
            if (event.action == InputService.DOWN) {
                isKey1Down = true
                if (!isKey2Down) {
                    // Fresh press on Key 1
                    assignedPid = pidProvider.getPid(shiftId)
                    service.injectEvent(key1X, key1Y, InputService.DOWN, assignedPid)
                    activeKey = 1
                } else if (activeKey == 2) {
                    // Shift from Key 2 -> Key 1 and hold
                    service.injectEvent(key1X, key1Y, InputService.MOVE, assignedPid)
                    activeKey = 1
                }
            } else if (event.action == InputService.UP) {
                isKey1Down = false
                if (isKey2Down) {
                    // Key 1 released, but Key 2 still held down -> shift back to Key 2 and hold
                    if (activeKey == 1) {
                        service.injectEvent(key2X, key2Y, InputService.MOVE, assignedPid)
                        activeKey = 2
                    }
                } else {
                    // Both keys released -> lift finger
                    val releaseX = if (activeKey == 2) key2X else key1X
                    val releaseY = if (activeKey == 2) key2Y else key1Y
                    service.injectEvent(releaseX, releaseY, InputService.UP, assignedPid)
                    pidProvider.releasePidFor(shiftId)
                    activeKey = 0
                    assignedPid = -1
                }
            }
        } else if (event.code == keycode2) {
            if (event.action == InputService.DOWN) {
                isKey2Down = true
                if (!isKey1Down) {
                    // Fresh press on Key 2
                    assignedPid = pidProvider.getPid(shiftId)
                    service.injectEvent(key2X, key2Y, InputService.DOWN, assignedPid)
                    activeKey = 2
                } else if (activeKey == 1) {
                    // Shift from Key 1 -> Key 2 and hold
                    service.injectEvent(key2X, key2Y, InputService.MOVE, assignedPid)
                    activeKey = 2
                }
            } else if (event.action == InputService.UP) {
                isKey2Down = false
                if (isKey1Down) {
                    // Key 2 released, but Key 1 still held down -> shift back to Key 1 and hold
                    if (activeKey == 2) {
                        service.injectEvent(key1X, key1Y, InputService.MOVE, assignedPid)
                        activeKey = 1
                    }
                } else {
                    // Both keys released -> lift finger
                    val releaseX = if (activeKey == 1) key1X else key2X
                    val releaseY = if (activeKey == 1) key1Y else key2Y
                    service.injectEvent(releaseX, releaseY, InputService.UP, assignedPid)
                    pidProvider.releasePidFor(shiftId)
                    activeKey = 0
                    assignedPid = -1
                }
            }
        }
    }
}
