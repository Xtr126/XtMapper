package com.xtr.keymapper.floatingkeys;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public class MovableFrameLayout extends FrameLayout implements View.OnTouchListener {
    private float dX, dY;

    public MovableFrameLayout(Context context) {
        super(context);
        init();
    }

    public MovableFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MovableFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnTouchListener(this);
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent){
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)view.getLayoutParams();

        int action = motionEvent.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                dX = view.getX() - motionEvent.getRawX();
                dY = view.getY() - motionEvent.getRawY();
                return true; // Consumed
            }
            case MotionEvent.ACTION_MOVE: {
                int viewWidth = view.getWidth();
                int viewHeight = view.getHeight();

                View viewParent = (View)view.getParent();
                int parentWidth = viewParent.getWidth();
                int parentHeight = viewParent.getHeight();

                float newX = motionEvent.getRawX() + dX;
                newX = Math.max(layoutParams.leftMargin, newX); // Don't allow the FAB past the left hand side of the parent
                newX = Math.min(parentWidth - viewWidth - layoutParams.rightMargin, newX); // Don't allow the FAB past the right hand side of the parent

                float newY = motionEvent.getRawY() + dY;
                newY = Math.max(layoutParams.topMargin, newY); // Don't allow the FAB past the top of the parent
                newY = Math.min(parentHeight - viewHeight - layoutParams.bottomMargin, newY); // Don't allow the FAB past the bottom of the parent

                view.animate()
                        .x(newX)
                        .y(newY)
                        .setDuration(0)
                        .start();
                return true; // Consumed
            }
            default:
                return super.onTouchEvent(motionEvent);
        }
    }
}