package xtr.keymapper.server.event

import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import xtr.keymapper.dpad.DpadHandler
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapConfig.Companion.KEY_ALT
import xtr.keymapper.keymap.KeymapConfig.Companion.KEY_CTRL
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfile.Companion.MAX_DPADS
import xtr.keymapper.keymap.element.Camera
import xtr.keymapper.keymap.element.Key
import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.keymap.element.SwipeKey
import xtr.keymapper.macro.Macro
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.pid.PidProvider
import xtr.keymapper.server.pid.PointerId.dpadpid1
import xtr.keymapper.shiftkey.ShiftKeyHandler
import xtr.keymapper.swipekey.SwipeKeyHandler

class KeyEventHandler(private val mInput: IInputInterface) {
    @JvmField
    @Volatile
    var ctrlKeyPressed = false
    @JvmField
    @Volatile
    var altKeyPressed = false
    private var dpadHandlers: Array<DpadHandler?>? = null
    private var swipeKeyHandlers: ArrayList<SwipeKeyHandler>? = null
    private var shiftKeyHandlers: ArrayList<ShiftKeyHandler>? = null
    private val pidProvider = PidProvider()
    private var mHandlerThread: HandlerThread? = null
    private var eventHandler: Handler? = null
    private val heldModifiers = mutableSetOf<String>()
    private val macroLock = Any()
    private val playback = mutableMapOf<String, Thread>()

    fun init() {
        mHandlerThread = HandlerThread("events").also { it.start() }
        eventHandler = Handler(mHandlerThread!!.looper)

        val keymapConfig = mInput.keymapConfig
        val profile = mInput.keymapProfile

        dpadHandlers = arrayOfNulls(MAX_DPADS)
        for (i in 0 until MAX_DPADS) {
            val pid = dpadpid1.id + i
            if (profile.dpadArray[i] != null) {
                dpadHandlers!![i] = DpadHandler(profile.dpadArray[i]!!, pid, eventHandler, keymapConfig.swipeDelayMs)
                dpadHandlers!![i]!!.setInterface(mInput)
            }
        }

        // Center offset is applied non-destructively in handleEvent when injecting touch

        swipeKeyHandlers = ArrayList()
        for (key in profile.swipeKeys) {
            swipeKeyHandlers!!.add(SwipeKeyHandler(key))
        }

        shiftKeyHandlers = ArrayList()
        for (key in profile.shiftKeys) {
            shiftKeyHandlers!!.add(ShiftKeyHandler(key))
        }
    }

    fun stop() {
        cancelPendingActions()
        dpadHandlers = null
        swipeKeyHandlers = null
        shiftKeyHandlers = null
        mHandlerThread?.quit()
        mHandlerThread = null
        eventHandler = null
    }

    internal fun cancelPendingActions() {
        eventHandler?.removeCallbacksAndMessages(null)
        shiftKeyHandlers?.forEach { it.stop(mInput, pidProvider) }
        synchronized(macroLock) {
            playback.forEach { (macroId, thread) ->
                thread.interrupt()
                pidProvider.releasePidFor(macroId)
            }
            playback.clear()
        }
        synchronized(heldModifiers) {
            heldModifiers.clear()
            ctrlKeyPressed = false
            altKeyPressed = false
        }
    }

    class KeyEvent {
        @JvmField
        var code: String = ""
        @JvmField
        var action: Int = 0
    }

    @Throws(RemoteException::class)
    fun handleEvent(line: String) {
        // line: EV_KEY KEY_X DOWN

        val event = getEvent(line) ?: return

        val keymapConfig = mInput.keymapConfig

        detectCtrlAltKeys(event)
        if (event.action == InputService.DOWN) if (handleKeyboardShortcuts(event.code)) return
        handleMouseAimAndCamera(event.code, event.action)
        if (event.code == keymapConfig.graveMouseAimShortcutKey &&
            event.action == InputService.DOWN && keymapConfig.keyGraveMouseAim
        ) {
            mInput.mouseEventHandler.triggerMouseAim()
            return
        }

        dpadHandlers?.forEach { dpadHandler ->
            dpadHandler?.handleEvent(event.code, event.action)
        }

        val keyList = mInput.keymapProfile.keys
        keyList.withIndex().forEach { (i, key) ->
            if (event.code == key.code)
                mInput.injectEvent(key.x + key.offset, key.y + key.offset, event.action, i)
        }

        swipeKeyHandlers?.forEach { swipeKeyHandler ->
            swipeKeyHandler.handleEvent(event, mInput, pidProvider, eventHandler, keymapConfig.swipeDelayMs)
        }

        shiftKeyHandlers?.forEach { shiftKeyHandler ->
            shiftKeyHandler.handleEvent(event, mInput, pidProvider, eventHandler, keymapConfig.shiftTransitionMs)
        }

        val macroIdMap = mInput.keymapProfile.macroIdMap
        val native = keymapConfig.usePhysicalWaylandShortcuts
        if (!native || event.action == InputService.DOWN)
            macroIdMap.forEach { (macroId, macro) ->
                if (event.code != "KEY_" + macro.triggerKey) return@forEach
                if (native) {
                    startCancellableMacro(macroId, macro)
                } else {
                    Thread {
                        try {
                            macro.runMacro(mInput, pidProvider.getPid(macroId))
                        } finally {
                            pidProvider.releasePidFor(macroId)
                        }
                    }.start()
                }
            }
    }

    private fun startCancellableMacro(id: String, macro: Macro) {
        synchronized(macroLock) {
            if (id in playback) return
            val pid = pidProvider.getPid(id)
            playback[id] = Thread({
                val current = Thread.currentThread()
                try {
                    macro.runMacro(mInput, pid) { x, y, action, pointerId ->
                        synchronized(macroLock) {
                            if (playback[id] == current) mInput.injectEvent(x, y, action, pointerId)
                        }
                    }
                } finally {
                    synchronized(macroLock) {
                        if (playback[id] == current) {
                            playback.remove(id)
                            pidProvider.releasePidFor(id)
                        }
                    }
                }
            }, "xtmapper-macro").also { it.start() }
        }
    }

    private fun detectCtrlAltKeys(event: KeyEvent) {
        val pressed = event.action == InputService.DOWN
        if (!mInput.keymapConfig.usePhysicalWaylandShortcuts) {
            if (event.code.contains("CTRL")) ctrlKeyPressed = pressed
            if (event.code.contains("ALT")) altKeyPressed = pressed
            return
        }
        when (event.code) {
            "KEY_LEFTCTRL", "KEY_RIGHTCTRL", "KEY_LEFTALT", "KEY_RIGHTALT" -> Unit
            else -> return
        }
        synchronized(heldModifiers) {
            if (pressed) heldModifiers.add(event.code) else heldModifiers.remove(event.code)
            ctrlKeyPressed = heldModifiers.any { it.contains("CTRL") }
            altKeyPressed = heldModifiers.any { it.contains("ALT") }
        }
    }

    private fun getEvent(line: String): KeyEvent? {
        val event = KeyEvent()
        // line: EV_KEY KEY_X DOWN (or with device prefix e.g. /dev/input/eventX: EV_KEY KEY_X DOWN)
        val tokens = line.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val evIndex = tokens.indexOf("EV_KEY")
        if (evIndex == -1 || evIndex + 2 >= tokens.size) return null
        event.code = tokens[evIndex + 1]
        if (!event.code.contains("KEY_")) return null

        when (tokens[evIndex + 2]) {
            "UP", "00000000" -> event.action = InputService.UP
            "DOWN", "00000001" -> event.action = InputService.DOWN
            else -> return null
        }
        return event
    }

    @Throws(RemoteException::class)
    private fun handleKeyboardShortcuts(keycode: String): Boolean {
        if (!(altKeyPressed || ctrlKeyPressed)) return false
        val modifier = if (ctrlKeyPressed) KEY_CTRL else KEY_ALT
        val keymapConfig = mInput.keymapConfig

        if (keymapConfig.launchEditorShortcutKeyModifier == modifier)
            if (keycode == keymapConfig.launchEditorShortcutKey) {
                mInput.callback.launchEditor()
                return true
            }

        if (keymapConfig.pauseResumeShortcutKeyModifier == modifier)
            if (keycode == keymapConfig.pauseResumeShortcutKey) {
                mInput.pauseResumeKeymap()
                return true
            }

        if (keymapConfig.switchProfileShortcutKeyModifier == modifier)
            if (keycode == keymapConfig.switchProfileShortcutKey) {
                mInput.callback.switchProfiles()
                return true
            }

        if (keymapConfig.isRootMode && keymapConfig.blockKeyboardShortcutKeyModifier == modifier)
            if (keycode == keymapConfig.blockKeyboardShortcutKey) {
                mInput.toggleKeyboardBlock()
                return true
            }
        return false
    }

    @Throws(RemoteException::class)
    fun handleKeyboardShortcutEvent(line: String) {
        val event = getEvent(line)
        if (event != null) {
            detectCtrlAltKeys(event)
            if (event.action == InputService.DOWN) handleKeyboardShortcuts(event.code)
        }
    }

    private fun handleMouseAimAndCamera(keycode: String, action: Int) {
        val keymapConfig = mInput.keymapConfig
        if (keycode == keymapConfig.mouseAimShortcutKey) {
            // if not toggle then hold down key to aim
            if (keymapConfig.mouseAimToggle && action == InputService.UP) return
            mInput.mouseEventHandler.triggerMouseAim()
        } else {
            val camera: Camera? = mInput.keymapProfile.camera
            if (camera != null) {
                if (keycode == camera.triggerKeyCode) {
                    // If not toggle then hold down key to move camera
                    if (camera.toggle && action == InputService.UP) return
                    mInput.mouseEventHandler.triggerCamera()
                }
            }
        }
    }
}
