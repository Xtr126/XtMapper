package xtr.keymapper.editor.component

import android.content.Context
import android.view.View
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.floatingkeys.MovableFloatingActionKey
import xtr.keymapper.keymap.KeymapConfig

class Key(
    callback: EditorUiComponentCallback,
    context: Context,
    key: xtr.keymapper.keymap.element.Key
) : EditorUiComponent(callback, context, key.x, key.y) {

    private val floatingKey: MovableFloatingActionKey = addKey(key)

    constructor(
        callback: EditorUiComponentCallback,
        context: Context,
        x: Float,
        y: Float
    ) : this(
        callback, context, xtr.keymapper.keymap.element.Key().also {
            it.code = "KEY_X"
            it.x = x
            it.y = y
        }
    )

    override fun toConfigLine(): String {
        return floatingKey.getData()
    }

    private fun addKey(key: xtr.keymapper.keymap.element.Key): MovableFloatingActionKey {
        val keymapConfig = KeymapConfig(context)

        val floatingKey = MovableFloatingActionKey(context, { floatingActionKey ->
            callback.removeComponent(this, floatingActionKey.frameView)
        }, callback.keysContainerView)

        floatingKey.setText(key.code!!.substring(4))
        floatingKey.frameView.animate()
            .x(key.x)
            .y(key.y)
            .setDuration(1000)
            .start()
        floatingKey.setOnClickListener(this::onFloatingKeyClick)

        floatingKey.frameView.scaleX = keymapConfig.floatingKeysSize
        floatingKey.frameView.scaleY = keymapConfig.floatingKeysSize

        return floatingKey
    }

    private fun onFloatingKeyClick(view: View) {
        callback.setOnKeyListener(EditorUI.KeyInFocusListener { floatingKey.setText(it!!) })
    }
}
