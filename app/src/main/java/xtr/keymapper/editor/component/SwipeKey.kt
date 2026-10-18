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
import xtr.keymapper.swipekey.SwipeKeyOverlay

class SwipeKey private constructor(
    mCallback: EditorUiComponentCallback,
    context: Context,
    swipeKey: xtr.keymapper.keymap.element.SwipeKey?,
    x: Float,
    y: Float
) : EditorUiComponent(mCallback, context, x, y) {

    private val swipeKeyView: SwipeKeyView

    init {
        val callback = object : SwipeKeyView.SwipeKeyCallback {
            override fun onSwipeKeyRemoved() {
                mCallback.removeComponent(this@SwipeKey)
            }

            override fun onViewClicked(view: MovableFloatingActionKey) {
                this@SwipeKey.callback.setOnKeyListener(EditorUI.KeyInFocusListener { view.setText(it!!) })
            }
        }
        swipeKeyView = if (swipeKey != null)
            SwipeKeyView(mCallback.keysContainerView, swipeKey, callback)
        else
            SwipeKeyView(mCallback.keysContainerView, callback)
    }

    constructor(mCallback: EditorUiComponentCallback, context: Context, x: Float, y: Float) :
        this(mCallback, context, null, x, y)

    constructor(
        mCallback: EditorUiComponentCallback,
        context: Context,
        swipeKey: xtr.keymapper.keymap.element.SwipeKey?
    ) : this(mCallback, context, swipeKey, 0f, 0f)

    override fun toConfigLine(): String {
        val swipeKey = xtr.keymapper.keymap.element.SwipeKey()
        swipeKey.key1.code = swipeKeyView.button1.getText()
        swipeKey.key1.x = swipeKeyView.button1.getX()
        swipeKey.key1.y = swipeKeyView.button1.getY()

        swipeKey.key2.code = swipeKeyView.button2.getText()
        swipeKey.key2.x = swipeKeyView.button2.getX()
        swipeKey.key2.y = swipeKeyView.button2.getY()

        return swipeKey.data
    }

    internal class SwipeKeyView {

        val button1: MovableFloatingActionKey
        val button2: MovableFloatingActionKey
        val closeButton: MaterialButton
        val overlay: SwipeKeyOverlay

        interface SwipeKeyCallback {
            fun onSwipeKeyRemoved()
            fun onViewClicked(view: MovableFloatingActionKey)
        }

        /* New swipe key */
        constructor(rootView: ViewGroup, callback: SwipeKeyCallback) {
            val context = rootView.context
            button1 = MovableFloatingActionKey(context, true, rootView)
            button2 = MovableFloatingActionKey(context, true, rootView)

            closeButton =
                SwipeKeyBinding.inflate(LayoutInflater.from(context), rootView, true).root

            overlay = SwipeKeyOverlay(context)
            rootView.addView(overlay)

            closeButton.setOnClickListener {
                rootView.removeView(button1.frameView)
                rootView.removeView(button2.frameView)
                rootView.removeView(closeButton)
                rootView.removeView(overlay)
                callback.onSwipeKeyRemoved()
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
            swipeKey: xtr.keymapper.keymap.element.SwipeKey,
            callback: SwipeKeyCallback
        ) : this(rootView, callback) {
            button1.setText(swipeKey.key1.code ?: "")
            button1.frameView.animate()
                .x(swipeKey.key1.x)
                .y(swipeKey.key1.y)
                .setDuration(500)
                .withEndAction { onXyChange(0f, 0f) }
                .start()

            button2.setText(swipeKey.key2.code ?: "")
            button2.frameView.animate()
                .x(swipeKey.key2.x)
                .y(swipeKey.key2.y)
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
