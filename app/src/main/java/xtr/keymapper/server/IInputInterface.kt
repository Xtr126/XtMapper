package xtr.keymapper.server

import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.server.event.KeyEventHandler
import xtr.keymapper.server.event.MouseEventHandler

interface IInputInterface {
    fun injectEvent(x: Float, y: Float, action: Int, pointerId: Int)
    fun injectHoverEvent(x: Float, y: Float, pointerId: Int)

    fun injectRightClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean)
    fun injectMiddleClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean)

    fun injectScroll(x: Float, y: Float, value: Float)
    fun pauseResumeKeymap()
    val keymapConfig: KeymapConfig
    val keyEventHandler: KeyEventHandler
    val mouseEventHandler: MouseEventHandler
    val keymapProfile: KeymapProfile
    val callback: IRemoteServiceCallback
    fun moveCursorX(x: Int)
    fun moveCursorY(y: Int)
    fun hideCursor()

    fun showCursor()
    fun toggleKeyboardBlock()
    val isKeyboardBlocked: Boolean
}
