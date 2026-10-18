package xtr.keymapper.editor.component

import android.content.Context
import xtr.keymapper.R
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.floatingkeys.MovableFloatingActionKey
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.keymap.element.Key

class RightClick(
    mCallback: EditorUiComponentCallback,
    context: Context,
    x: Float,
    y: Float
) : EditorUiComponent(mCallback, context, x, y) {

    private val rightClick: MovableFloatingActionKey = MovableFloatingActionKey(
        context, { floatingActionKey ->
            callback.removeComponent(this, floatingActionKey.frameView)
        }, mCallback.keysContainerView
    ).also {
        it.frameView.setBackgroundResource(R.drawable.ic_baseline_mouse_36)
        it.setText(R.string.right_click)
        it.frameView.animate().x(x).y(y)
            .setDuration(500)
            .start()
    }

    constructor(mCallback: EditorUiComponentCallback, context: Context, rightClick: Key?) :
        this(mCallback, context, rightClick!!.x, rightClick.y)

    override fun toConfigLine(): String {
        return KeymapProfiles.MOUSE_RIGHT + " " + rightClick.getX() + " " + rightClick.getY()
    }
}
