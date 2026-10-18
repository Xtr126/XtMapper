package xtr.keymapper.editor.component

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.databinding.DpadArrowsBinding
import xtr.keymapper.databinding.DpadBinding
import xtr.keymapper.dpad.DpadKeyCodes
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.floatingkeys.MovableFrameLayout

class Dpad(
    callback: EditorUiComponentCallback,
    context: Context,
    private val defaultX: Float,
    private val defaultY: Float
) : EditorUiComponent(callback, context, defaultX, defaultY) {

    private var dpadView: MovableFrameLayout? = null
    private var dpad: xtr.keymapper.keymap.element.Dpad? = null
    private var dpadBinding: DpadBinding? = null

    constructor(
        mCallback: EditorUiComponentCallback,
        context: Context,
        dpad: xtr.keymapper.keymap.element.Dpad
    ) : this(mCallback, context, dpad.getX(), dpad.getY()) {
        addDpad(dpad)
    }

    private fun addDpad(dpad: xtr.keymapper.keymap.element.Dpad) {
        this.dpad = dpad
        if (dpad.keycodes.equals(DpadKeyCodes.ARROW_KEYS))
            addArrowKeysDpad(dpad.getX(), dpad.getY())
        else
            addDpad(dpad.getX(), dpad.getY(), dpad.keycodes)
    }

    override fun toConfigLine(): String {
        // Create new dpad instance for updating properties
        this.dpad = if (dpad!!.keycodes.equals(DpadKeyCodes.ARROW_KEYS))
            xtr.keymapper.keymap.element.Dpad(
                dpadView!!,
                DpadKeyCodes.ARROW_KEYS,
                xtr.keymapper.keymap.element.Dpad.TAG_ARROW_KEYS
            )
        else
            xtr.keymapper.keymap.element.Dpad(
                dpadView!!,
                DpadKeyCodes(dpadBinding!!),
                xtr.keymapper.keymap.element.Dpad.TAG
            )

        return dpad!!.data
    }

    fun pickType(addComponent: (EditorUiComponent) -> Unit): Dpad {
        val items = arrayOf("Arrow keys", "WASD Keys", "Custom")
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle("Select Dpad").setItems(items) { _, i ->
            if (i == 0) addArrowKeysDpad(defaultX, defaultY)
            else if (i == 1) addWasdDpad(defaultX, defaultY)
            else addCustomDpad(defaultX, defaultY)
            addComponent(this)
        }
        val dialog: AlertDialog = builder.create()
        if (callback.isOverlayOpen()) dialog.window!!.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        )
        dialog.show()
        return this
    }

    private fun addArrowKeysDpad(x: Float, y: Float) {
        val dpadArrowsBinding =
            DpadArrowsBinding.inflate(layoutInflater, callback.keysContainerView, true)
        dpadView = dpadArrowsBinding.root

        dpadArrowsBinding.closeButton.setOnClickListener(this::onClose)
        dpadArrowsBinding.resizeHandle.setOnTouchListener(ResizeableDpadView(dpadView!!))

        if (dpad == null)
            this.dpad = xtr.keymapper.keymap.element.Dpad(
                dpadView!!,
                DpadKeyCodes.ARROW_KEYS,
                xtr.keymapper.keymap.element.Dpad.TAG_ARROW_KEYS
            )

        moveResizeDpad(x, y)
    }

    private fun addDpad(x: Float, y: Float, dpadKeycodes: DpadKeyCodes) {
        dpadBinding = DpadBinding.inflate(layoutInflater, callback.keysContainerView, true)
        setDpadKeys(dpadBinding!!, dpadKeycodes)
        dpadView = dpadBinding!!.root

        dpadBinding!!.closeButton.setOnClickListener(this::onClose)
        dpadBinding!!.resizeHandle.setOnTouchListener(ResizeableDpadView(dpadView!!))

        if (this.dpad == null) {
            this.dpad = xtr.keymapper.keymap.element.Dpad(
                dpadView!!,
                DpadKeyCodes(dpadBinding!!),
                xtr.keymapper.keymap.element.Dpad.TAG
            )
        }

        moveResizeDpad(x, y)
    }

    private fun addWasdDpad(x: Float, y: Float) {
        addDpad(x, y, DpadKeyCodes.WASD_KEYS)
    }

    private fun addCustomDpad(x: Float, y: Float) {
        addDpad(x, y, DpadKeyCodes.IJKL_KEYS)
    }

    private fun setDpadKeys(binding: DpadBinding, dpadKeycodes: DpadKeyCodes) {
        // strip KEY_
        binding.keyUp.text = dpadKeycodes.Up.substring(4)
        binding.keyDown.text = dpadKeycodes.Down.substring(4)
        binding.keyLeft.text = dpadKeycodes.Left.substring(4)
        binding.keyRight.text = dpadKeycodes.Right.substring(4)
        for (key in arrayOf(binding.keyUp, binding.keyDown, binding.keyRight, binding.keyLeft)) {
            key.setOnClickListener { view ->
                callback.setOnKeyListener { k -> (view as TextView).setText(k) }
            }
        }
    }

    private fun moveResizeDpad(x: Float, y: Float) {
        dpadView!!.animate().x(x).y(y)
            .setDuration(500)
            .start()

        // resize dpad from saved profile configuration
        val x1 = dpad!!.getWidth() - dpadView!!.layoutParams.width
        val y1 = dpad!!.getHeight() - dpadView!!.layoutParams.height
        EditorUI.resizeView(dpadView!!, x1, y1)
    }

    private fun onClose(v: View) {
        callback.removeComponent(this, dpadView)
        dpadView = null
        dpadBinding = null
    }

    internal class ResizeableDpadView(private val rootView: View) : View.OnTouchListener {
        private var defaultPivotX = 0f
        private var defaultPivotY = 0f

        private fun getDefaultPivotXY() {
            defaultPivotX = rootView.pivotX
            defaultPivotY = rootView.pivotY
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_MOVE) {
                // Resize View in fixed ratio
                val newSize = (event.x.toInt() + event.y.toInt()) / 2
                EditorUI.resizeView(rootView, newSize, newSize)

                // Resize View from center point
                if (defaultPivotX > 0) {
                    val newPivotX = rootView.pivotX - defaultPivotX
                    val newPivotY = rootView.pivotY - defaultPivotY
                    rootView.x = rootView.x - newPivotX
                    rootView.y = rootView.y - newPivotY
                }
                getDefaultPivotXY()
            }
            return v.onTouchEvent(event)
        }
    }
}
