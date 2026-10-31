package xtr.keymapper.floatingkeys

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import xtr.keymapper.databinding.FloatingKeyBinding
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MovableFloatingActionKey(context: Context, parent: ViewGroup?) : View.OnTouchListener {

    private var dX = 0f
    private var dY = 0f

    @JvmField
    val textView: TextView

    @JvmField
    val frameView: FrameLayout

    private var xyChangeListener: OnXyChangeListener? = null

    @JvmField
    val closeButton: MaterialButton

    @JvmField
    val binding: FloatingKeyBinding

    private var callback: OnKeyRemoved? = null
    private var isSwipeKey = false
    private var pendingPositionX: Float? = null
    private var pendingPositionY: Float? = null

    fun interface OnKeyRemoved {
        fun onKeyRemoved(key: MovableFloatingActionKey)
    }

    init {
        binding = FloatingKeyBinding.inflate(LayoutInflater.from(context), parent, true)
        frameView = binding.root
        frameView.setOnTouchListener(this)

        closeButton = binding.closeButton
        textView = binding.textView

        closeButton.setOnClickListener {
            if (!isSwipeKey) {
                frameView.removeAllViews()
                frameView.invalidate()
                callback?.onKeyRemoved(this)
            } else {
                setText(" ")
            }
        }
    }

    constructor(context: Context, callback: OnKeyRemoved, parent: ViewGroup?) : this(context, parent) {
        this.callback = callback
    }

    constructor(context: Context, isSwipeKey: Boolean, parent: ViewGroup?) : this(context, parent) {
        this.isSwipeKey = isSwipeKey
    }

    fun getData(): String {
        finishPendingPositionAnimation()
        return "KEY_" + getText() + " " + frameView.x + " " + frameView.y + " " + frameView.pivotX
    }

    /** Animates to a saved position while keeping its final coordinates available for snapshots. */
    fun animateToPosition(x: Float, y: Float, durationMillis: Long, onEnd: (() -> Unit)? = null) {
        pendingPositionX = x
        pendingPositionY = y
        frameView.animate().x(x).y(y).setDuration(durationMillis).withEndAction {
            if (pendingPositionX == x && pendingPositionY == y) {
                pendingPositionX = null
                pendingPositionY = null
            }
            onEnd?.invoke()
        }.start()
    }

    /** Ensures serialization and direct manipulation use the final position, not an in-flight frame. */
    fun finishPendingPositionAnimation() {
        val targetX = pendingPositionX ?: return
        val targetY = pendingPositionY ?: return
        pendingPositionX = null
        pendingPositionY = null
        frameView.animate().cancel()
        frameView.x = targetX
        frameView.y = targetY
        xyChangeListener?.onNewXY(targetX, targetY)
    }

    fun setText(s: CharSequence) {
        textView.text = s
    }

    fun setText(@StringRes resId: Int) {
        textView.setText(resId)
    }

    fun getText(): String {
        return textView.text.toString().uppercase(Locale.getDefault())
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        val layoutParams = view.layoutParams as ViewGroup.MarginLayoutParams
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                finishPendingPositionAnimation()
                dX = view.x - motionEvent.rawX
                dY = view.y - motionEvent.rawY
                return true // Consumed
            }
            MotionEvent.ACTION_UP -> {
                return frameView.performClick()
            }
            MotionEvent.ACTION_MOVE -> {

                val viewParent = view.parent as View
                val parentWidth = viewParent.width
                val parentHeight = viewParent.height

                var newX = motionEvent.rawX + dX
                newX = max(layoutParams.leftMargin - view.pivotX, newX) // Don't allow the FAB past the left hand side of the parent
                newX = min(parentWidth - view.pivotX - layoutParams.rightMargin, newX) // Don't allow the FAB past the right hand side of the parent

                var newY = motionEvent.rawY + dY
                newY = max(layoutParams.topMargin - view.pivotY, newY) // Don't allow the FAB past the top of the parent
                newY = min(parentHeight - view.pivotY - layoutParams.bottomMargin, newY) // Don't allow the FAB past the bottom of the parent

                view.animate().x(newX).y(newY).setDuration(0).start()

                xyChangeListener?.onNewXY(newX, newY)

                return true // Consumed
            }
            else -> return frameView.onTouchEvent(motionEvent)
        }
    }

    fun setXyChangeListener(xyChangeListener: OnXyChangeListener) {
        this.xyChangeListener = xyChangeListener
        xyChangeListener.onNewXY(frameView.x, frameView.y)
    }

    fun interface OnXyChangeListener {
        fun onNewXY(x: Float, y: Float)
    }

    fun setX(x: Float) {
        frameView.x = x
    }

    fun setY(y: Float) {
        frameView.y = y
    }

    fun getX(): Float {
        finishPendingPositionAnimation()
        return frameView.x
    }

    fun getY(): Float {
        finishPendingPositionAnimation()
        return frameView.y
    }

    fun setOnClickListener(listener: View.OnClickListener?) {
        frameView.setOnClickListener(listener)
    }
}
