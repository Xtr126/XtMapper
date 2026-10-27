package xtr.keymapper.editor.component

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.R
import xtr.keymapper.databinding.ShiftKeyControlsBinding
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
        shiftKey.durationMs = shiftKeyView.durationMs

        return shiftKey.data
    }

    internal class ShiftKeyView {

        val button1: MovableFloatingActionKey
        val button2: MovableFloatingActionKey
        val controlsBinding: ShiftKeyControlsBinding
        val controlsContainer: View
        val overlay: ShiftKeyOverlay
        var durationMs: Int = -1

        interface ShiftKeyCallback {
            fun onShiftKeyRemoved()
            fun onViewClicked(view: MovableFloatingActionKey)
        }

        /* New shift key */
        constructor(rootView: ViewGroup, callback: ShiftKeyCallback) {
            val context = rootView.context
            button1 = MovableFloatingActionKey(context, true, rootView)
            button2 = MovableFloatingActionKey(context, true, rootView)

            controlsBinding =
                ShiftKeyControlsBinding.inflate(LayoutInflater.from(context), rootView, true)
            controlsContainer = controlsBinding.root

            overlay = ShiftKeyOverlay(context)
            rootView.addView(overlay)

            controlsBinding.deleteButton.setOnClickListener {
                rootView.removeView(button1.frameView)
                rootView.removeView(button2.frameView)
                rootView.removeView(controlsContainer)
                rootView.removeView(overlay)
                callback.onShiftKeyRemoved()
            }

            controlsBinding.settingsButton.setOnClickListener {
                showSpeedDialog(context)
            }

            button1.setX(rootView.pivotX - 100)
            button1.setY(rootView.pivotY - 100)

            button2.setX(rootView.pivotX + 100)
            button2.setY(rootView.pivotY + 100)

            button1.setXyChangeListener(this::onXyChange)
            button2.setXyChangeListener(this::onXyChange)

            button1.setOnClickListener { callback.onViewClicked(button1) }
            button2.setOnClickListener { callback.onViewClicked(button2) }

            controlsContainer.post { onXyChange(0f, 0f) }
        }

        /* From saved profile */
        constructor(
            rootView: ViewGroup,
            shiftKey: xtr.keymapper.keymap.element.ShiftKey,
            callback: ShiftKeyCallback
        ) : this(rootView, callback) {
            durationMs = shiftKey.durationMs
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
            overlay.centerViewOnLine(controlsContainer)
        }

        private fun showSpeedDialog(context: Context) {
            val keymapConfig = KeymapConfig(context)
            val globalSpeed = keymapConfig.shiftTransitionMs

            val speeds = intArrayOf(-1, 0, 50, 100, 150, 200, 300)
            val labels = arrayOf(
                context.getString(R.string.use_global_default, globalSpeed),
                "Instant (0 ms)",
                "50 ms",
                "100 ms",
                "150 ms",
                "200 ms",
                "300 ms"
            )

            val currentSelection = when (durationMs) {
                -1 -> 0
                0 -> 1
                50 -> 2
                100 -> 3
                150 -> 4
                200 -> 5
                300 -> 6
                else -> -1
            }

            val builder = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.shift_transition_title)
                .setSingleChoiceItems(labels, currentSelection) { dialog, which ->
                    durationMs = speeds[which]
                    val chosenText = if (durationMs == -1) {
                        "Global default ($globalSpeed ms)"
                    } else {
                        "$durationMs ms"
                    }
                    android.widget.Toast.makeText(context, "Shift transition: $chosenText", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)

            val dialog = builder.create()
            if (context !is android.app.Activity) {
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                dialog.window?.setType(windowType)
            }
            dialog.show()
        }
    }
}
