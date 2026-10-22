package xtr.keymapper.editor.component

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import xtr.keymapper.databinding.SwipeKeyBinding
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.floatingkeys.MovableFloatingActionKey
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.shiftkey.ShiftKeyOverlay

class ShiftKey private constructor(
    mCallback: EditorUiComponentCallback,
    context: Context,
    shiftKey: xtr.keymapper.keymap.element.ShiftKey?,
    x: Float,
    y: Float
) : EditorUiComponent(mCallback, context, x, y) {

    private val shiftKeyView: ShiftKeyView

    init {
        val callback = object : ShiftKeyView.ShiftKeyCallback {
            override fun onShiftKeyRemoved() {
                mCallback.removeComponent(this@ShiftKey)
            }

            override fun onViewClicked(view: MovableFloatingActionKey) {
                this@ShiftKey.callback.setOnKeyListener(EditorUI.KeyInFocusListener { view.setText(it!!) })
            }
        }
        shiftKeyView = if (shiftKey != null)
            ShiftKeyView(mCallback.keysContainerView, shiftKey, callback)
        else
            ShiftKeyView(mCallback.keysContainerView, callback)
    }

    constructor(mCallback: EditorUiComponentCallback, context: Context, x: Float, y: Float) :
        this(mCallback, context, null, x, y)

    constructor(
        mCallback: EditorUiComponentCallback,
        context: Context,
        shiftKey: xtr.keymapper.keymap.element.ShiftKey?
    ) : this(mCallback, context, shiftKey, 0f, 0f)

    override fun toConfigLine(): String {
        val shiftKey = xtr.keymapper.keymap.element.ShiftKey()
        shiftKey.key1.code = shiftKeyView.button1.getText()
        shiftKey.key1.x = shiftKeyView.button1.getX()
        shiftKey.key1.y = shiftKeyView.button1.getY()

        shiftKey.key2.code = shiftKeyView.button2.getText()
        shiftKey.key2.x = shiftKeyView.button2.getX()
        shiftKey.key2.y = shiftKeyView.button2.getY()

        return shiftKey.data
    }

    internal class ShiftKeyView {

        val button1: MovableFloatingActionKey
        val button2: MovableFloatingActionKey
        val closeButton: MaterialButton
        val overlay: ShiftKeyOverlay

        interface ShiftKeyCallback {
            fun onShiftKeyRemoved()
            fun onViewClicked(view: MovableFloatingActionKey)
        }

        /* New shift key */
        constructor(rootView: ViewGroup, callback: ShiftKeyCallback) {
            val context = rootView.context
            button1 = MovableFloatingActionKey(context, true, rootView)
            button2 = MovableFloatingActionKey(context, true, rootView)

            closeButton =
                SwipeKeyBinding.inflate(LayoutInflater.from(context), rootView, true).root

            overlay = ShiftKeyOverlay(context)
            rootView.addView(overlay)

            closeButton.setOnClickListener {
                rootView.removeView(button1.frameView)
                rootView.removeView(button2.frameView)
                rootView.removeView(closeButton)
                rootView.removeView(overlay)
                callback.onShiftKeyRemoved()
            }

            button1.setX(rootView.pivotX - 100)
            button1.setY(rootView.pivotY - 100)

            button2.setX(rootView.pivotX + 100)
            button2.setY(rootView.pivotY + 100)

            button1.setXyChangeListener(this::onXyChange)
            button2.setXyChangeListener(this::onXyChange)

            button1.setOnClickListener { callback.onViewClicked(button1) }
            button2.setOnClickListener { callback.onViewClicked(button2) }
        }

        /* From saved profile */
        constructor(
            rootView: ViewGroup,
            shiftKey: xtr.keymapper.keymap.element.ShiftKey,
            callback: ShiftKeyCallback
        ) : this(rootView, callback) {
            button1.setText(shiftKey.key1.code ?: "")
            button1.frameView.animate()
                .x(shiftKey.key1.x)
                .y(shiftKey.key1.y)
                .setDuration(500)
                .withEndAction { onXyChange(0f, 0f) }
                .start()

            button2.setText(shiftKey.key2.code ?: "")
            button2.frameView.animate()
                .x(shiftKey.key2.x)
                .y(shiftKey.key2.y)
                .setDuration(500)
                .withEndAction { onXyChange(0f, 0f) }
                .start()

            val keymapConfig = KeymapConfig(rootView.context)

            button1.frameView.scaleX = keymapConfig.floatingKeysSize
            button1.frameView.scaleY = keymapConfig.floatingKeysSize

            button2.frameView.scaleX = keymapConfig.floatingKeysSize
            button2.frameView.scaleY = keymapConfig.floatingKeysSize
        }

        private fun onXyChange(x: Float, y: Float) {
            overlay.setLineXyFrom(button1.frameView, button2.frameView)
            overlay.centerViewOnLine(closeButton)
        }
    }
}
