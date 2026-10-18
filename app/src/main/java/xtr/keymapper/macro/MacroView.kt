package xtr.keymapper.macro

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Custom View for visualization
 */
class MacroView(context: Context, private val onFinishListener: OnFinishListener) : View(context) {

    private val paintOuter: Paint
    private val paintInner: Paint
    private val path = Path()
    private val stringBuilder = StringBuilder()
    private var lastEventTime = -1L

    init {
        // Set up paint
        paintOuter = Paint()
        paintOuter.isAntiAlias = true
        paintOuter.color = Color.GREEN
        paintOuter.style = Paint.Style.STROKE

        paintOuter.strokeWidth = 5f
        paintOuter.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)

        paintInner = Paint(paintOuter)
        paintInner.strokeWidth = 2f
        paintInner.color = Color.CYAN
        paintInner.maskFilter = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paintOuter) // Draw the path
        canvas.drawPath(path, paintInner) // Draw the path
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.source == InputDevice.SOURCE_KEYBOARD) {
            clearCanvasAndFinish()
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        var elapsedTimeSinceLastEventMillis = 0L

        if (lastEventTime > 0)
            elapsedTimeSinceLastEventMillis = event.eventTime - lastEventTime

        lastEventTime = event.eventTime

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y) // Start new path
                path.lineTo(x, y) // Java switch fell through to MOVE
            }
            MotionEvent.ACTION_MOVE -> path.lineTo(x, y) // Draw line to current touch point
            MotionEvent.ACTION_UP -> {
                path.moveTo(x, y) // Start new path
                return false // Java switch fell through to default
            }
            else -> return false
        }
        logEvent(x, y, elapsedTimeSinceLastEventMillis)

        invalidate() // Request redraw
        return true
    }

    private fun logEvent(x: Float, y: Float, elapsedTimeSinceLastEventMillis: Long) {
        stringBuilder.append(x).append(' ')
            .append(y).append(' ')
            .append(elapsedTimeSinceLastEventMillis)
            .append(";")
    }

    fun clearCanvasAndFinish() {
        path.reset()
        invalidate() // Request redraw

        MacroSharedPreferences(context)
            .addMacroWithNextAvailableId(stringBuilder.toString())

        onFinishListener.onFinishMacro(this)
    }

    fun interface OnFinishListener {
        /**
         * @param macroView View with the macro visuals
         */
        fun onFinishMacro(macroView: MacroView?)
    }
}
