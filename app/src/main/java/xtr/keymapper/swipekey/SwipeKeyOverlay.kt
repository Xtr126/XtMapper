package xtr.keymapper.swipekey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.UiThread

class SwipeKeyOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f, 10f, 15f, 20f), 0f)
    }
    private var lineStartX = 0f
    private var lineStartY = 0f
    private var lineStopX = 0f
    private var lineStopY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(lineStartX, lineStartY, lineStopX, lineStopY, mPaint)
    }

    @UiThread
    fun setLineXyFrom(view1: View, view2: View) {
        lineStartX = view1.x + view1.pivotX
        lineStartY = view1.y + view1.pivotY

        lineStopX = view2.x + view2.pivotX
        lineStopY = view2.y + view2.pivotY
        invalidate()
    }

    private val lineMidPointX get() = (lineStartX + lineStopX) / 2
    private val lineMidPointY get() = (lineStartY + lineStopY) / 2

    fun centerViewOnLine(view: View) {
        view.x = lineMidPointX - view.pivotX
        view.y = lineMidPointY - view.pivotY
    }
}
