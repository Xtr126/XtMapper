package xtr.keymapper.shiftkey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.UiThread

class ShiftKeyOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 165, 0) // Orange / Amber to distinguish from SwipeKey
        strokeWidth = 6f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
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
        val halfW = if (view.width > 0) view.width / 2f else if (view.measuredWidth > 0) view.measuredWidth / 2f else view.pivotX
        val halfH = if (view.height > 0) view.height / 2f else if (view.measuredHeight > 0) view.measuredHeight / 2f else view.pivotY
        view.x = lineMidPointX - halfW
        view.y = lineMidPointY - halfH
    }
}
