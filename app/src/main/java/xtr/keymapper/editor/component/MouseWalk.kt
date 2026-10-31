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
    private var positioned = false
    private var pendingCenterX = defaultX
    private var pendingCenterY = defaultY

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
        val view = rootView!!
        if (!positioned) {
            // Imported profiles are saved immediately after reloadKeymap(), before the
            // posted layout/position callback can run. Preserve their trusted parsed model
            // rather than replacing it with the newly inflated view's (0, 0) coordinates.
            mouseWalk?.let { saved ->
                return xtr.keymapper.keymap.element.MouseWalk.TAG + " " +
                    saved.x + " " + saved.y + " " + saved.radius
            }

            // A brand-new component can also be saved before its post callback. Its layout
            // dimensions are already available in LayoutParams even if the view isn't laid out.
            val params = view.layoutParams
            val width = view.width.takeIf { it > 0 } ?: params.width
            val height = view.height.takeIf { it > 0 } ?: params.height
            val radius = if (width > 0 && height > 0) (width + height) / 4f else 0f
            return xtr.keymapper.keymap.element.MouseWalk.TAG + " " +
                pendingCenterX + " " + pendingCenterY + " " + radius
        }
        view.finishPendingPositionAnimation()
        // Create new instance for updating properties
        this.mouseWalk = xtr.keymapper.keymap.element.MouseWalk(view)
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

        pendingCenterX = x
        pendingCenterY = y
        rootView!!.post {
            if (this.mouseWalk == null) {
                this.mouseWalk = xtr.keymapper.keymap.element.MouseWalk(rootView!!)
            }
            moveResizeDpad(x, y)
            positioned = true
        }
    }

    private fun moveResizeDpad(x: Float, y: Float) {
        rootView!!.animateToPosition(x - mouseWalk!!.radius, y - mouseWalk!!.radius, 500L)

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
                (rootView as? MovableFrameLayout)?.finishPendingPositionAnimation()
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
