package com.xtr.keymapper.layout;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class FloatingActionKey extends FrameLayout implements View.OnTouchListener {

    public MovableFloatingActionButton key;

    private final static float CLICK_DRAG_TOLERANCE = 10; // Often, there will be a slight, unintentional, drag when the user taps the FAB, so we need to account for this.

    private float downRawX, downRawY;
    private float dX, dY;

    public FloatingActionKey(Context context) {
        super(context);
        init();
    }

    public FloatingActionKey(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FloatingActionKey(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnTouchListener(this);
        setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        key = new MovableFloatingActionButton(getContext());
        key.setClickable(false);
        key.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        key.setElevation(1);

        MovableFloatingActionButton closeButton = new MovableFloatingActionButton(getContext());
        closeButton.setImageResource(android.R.drawable.ic_delete);
        closeButton.setElevation(2);
        closeButton.setOnClickListener(v -> {
            removeAllViews();
            key = null;
        });
        closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        LayoutParams layoutParams = new LayoutParams(20, 20);
        layoutParams.gravity = Gravity.BOTTOM | Gravity.END;
        closeButton.setLayoutParams(layoutParams);

        addView(key);
        addView(closeButton);
    }

    public String getData(){
        return "KEY_" + key.key.toUpperCase() + " " + getX() + " " + getY() + "\n";
    }

    public void setText(String s) {
        key.setText(s);
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent){
        key.setButtonActive();

        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)view.getLayoutParams();

        int action = motionEvent.getAction();
        if (action == MotionEvent.ACTION_DOWN) {

            key.setButtonActive();
            downRawX = motionEvent.getRawX();
            downRawY = motionEvent.getRawY();
            dX = view.getX() - downRawX;
            dY = view.getY() - downRawY;
            return true; // Consumed
        }
        else if (action == MotionEvent.ACTION_MOVE) {

            key.setButtonActive();
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
        else if (action == MotionEvent.ACTION_UP) {

            key.setButtonInactive();
            float upRawX = motionEvent.getRawX();
            float upRawY = motionEvent.getRawY();

            float upDX = upRawX - downRawX;
            float upDY = upRawY - downRawY;

            if (Math.abs(upDX) < CLICK_DRAG_TOLERANCE && Math.abs(upDY) < CLICK_DRAG_TOLERANCE) { // A click
                return performClick();
            }
            else { // A drag
                return true; // Consumed
            }

        }
        else {
            return super.onTouchEvent(motionEvent);
        }
    }

}