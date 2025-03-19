package xtr.keymapper.macro;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Custom View for visualization
 */
public class MacroView extends View {

    private static final char DELIMITER = ',';
    private final Paint paintOuter;
    private final Paint paintInner;
    private final Path path;
    private final StringBuilder stringBuilder = new StringBuilder();
    private final OnFinishListener onFinishListener;

    public MacroView(Context context, OnFinishListener onFinishListener) {
        super(context);
        this.onFinishListener = onFinishListener;
        // Set up paint
        paintOuter = new Paint();
        paintOuter.setAntiAlias(true);
        paintOuter.setColor(Color.GREEN);
        paintOuter.setStyle(Paint.Style.STROKE);

        paintOuter.setStrokeWidth(5f);
        paintOuter.setMaskFilter(new BlurMaskFilter(10, BlurMaskFilter.Blur.NORMAL));

        paintInner = new Paint(paintOuter);
        paintInner.setStrokeWidth(2f);
        paintInner.setColor(Color.CYAN);
        paintInner.setMaskFilter(null);

        // Set up path
        path = new Path();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawPath(path, paintOuter);  // Draw the path
        canvas.drawPath(path, paintInner);  // Draw the path
    }

    public boolean onKey(KeyEvent event) {
        if (event.getSource() == InputDevice.SOURCE_KEYBOARD) {
            clearCanvasAndFinish();
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        long elapsedTimeMillis = event.getEventTime() - event.getDownTime();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                path.moveTo(x, y);  // Start new path
            case MotionEvent.ACTION_MOVE:
                path.lineTo(x, y);  // Draw line to current touch point
                break;
            case MotionEvent.ACTION_UP:
                path.moveTo(x, y);  // Start new path
            default:
                return false;
        }
        logEvent(x, y, elapsedTimeMillis);

        invalidate();  // Request redraw
        return true;
    }

    private void logEvent(float x, float y, long elapsedTimeMillis) {
        stringBuilder.append(x).append(DELIMITER)
                .append(y).append(DELIMITER)
                .append(elapsedTimeMillis).append(DELIMITER)
                .append("\n");
    }


    public void clearCanvasAndFinish() {
        path.reset();
        invalidate();  // Request redraw

        new MacroSharedPreferences(getContext())
                .addMacroWithNextAvailableId(stringBuilder.toString());

        onFinishListener.onFinishMacro(this);
    }


    public interface OnFinishListener {
        /**
         * @param macroView View with the macro visuals
         */
        void onFinishMacro(MacroView macroView);
    }
}
