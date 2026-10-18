package xtr.keymapper.editor.component

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import xtr.keymapper.databinding.MouseWalkBinding
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.floatingkeys.MovableFrameLayout

class MouseWalk private constructor(
    callback: EditorUiComponentCallback,
    context: Context,
    defaultX: Float,
    defaultY: Float,
    saved: xtr.keymapper.keymap.element.MouseWalk?
) : EditorUiComponent(callback, context, defaultX, defaultY) {

    private var rootView: MovableFrameLayout? = null
    private var mouseWalk: xtr.keymapper.keymap.element.MouseWalk? = null
    private var binding: MouseWalkBinding? = null

    // Inflate exactly once per constructor, as in the Java original
    init {
        if (saved != null) addMouseWalk(saved) else addMouseWalk(defaultX, defaultY)
    }

    constructor(callback: EditorUiComponentCallback, context: Context, defaultX: Float, defaultY: Float) :
        this(callback, context, defaultX, defaultY, null)

    constructor(
        mCallback: EditorUiComponentCallback,
        context: Context,
        mouseWalk: xtr.keymapper.keymap.element.MouseWalk?
    ) : this(mCallback, context, mouseWalk!!.x, mouseWalk.y, mouseWalk)

    private fun addMouseWalk(mouseWalk: xtr.keymapper.keymap.element.MouseWalk) {
        this.mouseWalk = mouseWalk
        addMouseWalk(mouseWalk.x, mouseWalk.y)
    }

    override fun toConfigLine(): String {
        // Create new instance for updating properties
        this.mouseWalk = xtr.keymapper.keymap.element.MouseWalk(rootView!!)
        return xtr.keymapper.keymap.element.MouseWalk.TAG + " " +
                mouseWalk!!.x + " " +
                mouseWalk!!.y + " " +
                mouseWalk!!.radius
    }

    private fun addMouseWalk(x: Float, y: Float) {
        binding = MouseWalkBinding.inflate(layoutInflater, callback.keysContainerView, true)
        rootView = binding!!.root

        binding!!.closeButton.setOnClickListener(this::onClose)
        binding!!.resizeHandle.setOnTouchListener(ResizeableDpadView(rootView!!))

        rootView!!.post {
            if (this.mouseWalk == null) {
                this.mouseWalk = xtr.keymapper.keymap.element.MouseWalk(rootView!!)
            }
            moveResizeDpad(x, y)
        }
    }

    private fun moveResizeDpad(x: Float, y: Float) {
        rootView!!.animate().x(x - mouseWalk!!.radius).y(y - mouseWalk!!.radius)
            .setDuration(500)
            .start()

        // resize dpad from saved profile configuration
        val x1 = (mouseWalk!!.radius * 2) - rootView!!.layoutParams.width
        val y1 = (mouseWalk!!.radius * 2) - rootView!!.layoutParams.height
        EditorUI.resizeView(rootView!!, x1.toInt(), y1.toInt())
    }

    private fun onClose(v: View) {
        callback.removeComponent(this, rootView)
        rootView = null
        binding = null
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
