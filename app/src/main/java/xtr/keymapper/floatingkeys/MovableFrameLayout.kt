package xtr.keymapper.floatingkeys

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

class MovableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), View.OnTouchListener {

    private var dX = 0f
    private var dY = 0f
    private var pendingPositionX: Float? = null
    private var pendingPositionY: Float? = null

    init {
        setOnTouchListener(this)
    }

    fun animateToPosition(x: Float, y: Float, durationMillis: Long) {
        pendingPositionX = x
        pendingPositionY = y
        animate().x(x).y(y).setDuration(durationMillis).withEndAction {
            if (pendingPositionX == x && pendingPositionY == y) {
                pendingPositionX = null
                pendingPositionY = null
            }
        }.start()
    }

    fun finishPendingPositionAnimation() {
        val targetX = pendingPositionX ?: return
        val targetY = pendingPositionY ?: return
        pendingPositionX = null
        pendingPositionY = null
        animate().cancel()
        x = targetX
        y = targetY
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
            MotionEvent.ACTION_MOVE -> {
                val viewWidth = view.width
                val viewHeight = view.height

                val viewParent = view.parent as View
                val parentWidth = viewParent.width
                val parentHeight = viewParent.height

                var newX = motionEvent.rawX + dX
                newX = max(layoutParams.leftMargin.toFloat(), newX) // Don't allow the FAB past the left hand side of the parent
                newX = min(parentWidth - viewWidth - layoutParams.rightMargin.toFloat(), newX) // Don't allow the FAB past the right hand side of the parent

                var newY = motionEvent.rawY + dY
                newY = max(layoutParams.topMargin.toFloat(), newY) // Don't allow the FAB past the top of the parent
                newY = min(parentHeight - viewHeight - layoutParams.bottomMargin.toFloat(), newY) // Don't allow the FAB past the bottom of the parent

                pendingPositionX = null
                pendingPositionY = null
                view.animate()
                    .x(newX)
                    .y(newY)
                    .setDuration(0)
                    .start()
                return true // Consumed
            }
            else -> return view.onTouchEvent(motionEvent)
        }
    }
}
