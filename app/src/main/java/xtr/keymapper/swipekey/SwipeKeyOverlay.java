package xtr.keymapper.swipekey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.UiContext;
import androidx.annotation.UiThread;

import com.google.android.material.button.MaterialButton;

public class SwipeKeyOverlay extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lineStartX, lineStartY;
    private float lineStopX, lineStopY;

    public SwipeKeyOverlay(Context context) {
        this(context, null);
    }

    public SwipeKeyOverlay(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeKeyOverlay(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mPaint.setColor(Color.CYAN);
        mPaint.setStrokeWidth(5f);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setPathEffect(new DashPathEffect(new float[]{5, 10, 15, 20}, 0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(lineStartX, lineStartY, lineStopX, lineStopY, mPaint);
    }

    @UiThread
    public void setLineXyFrom(View view1, View view2) {
        this.lineStartX = view1.getX() + view1.getPivotX();
        this.lineStartY = view1.getY() + view1.getPivotY();

        this.lineStopX = view2.getX() + view2.getPivotX();
        this.lineStopY = view2.getY() + view2.getPivotY();
        invalidate();
    }

    private float getLineMidPointX(){
        return (lineStartX + lineStopX) / 2;
    }
    private float getLineMidPointY(){
        return (lineStartY + lineStopY) / 2;
    }

    public void centerViewOnLine(View view) {
        view.setX(getLineMidPointX() - view.getPivotX());
        view.setY(getLineMidPointY() - view.getPivotY());
    }
}
