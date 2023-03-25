package xtr.keymapper.floatingkeys;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class MovableFloatingActionKey extends FrameLayout implements View.OnTouchListener {

    public FloatingActionKey key;
    private float dX, dY;
    private OnXyChangeListener xyChangeListener;
    public FloatingActionKey closeButton;
    private OnKeyRemoved callback;
    boolean isSwipeKey;

    public interface OnKeyRemoved {

        void onKeyRemoved(MovableFloatingActionKey key);
    }
    public MovableFloatingActionKey(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MovableFloatingActionKey(Context context) {
        super(context);
        init();
    }
    public MovableFloatingActionKey(Context context, OnKeyRemoved callback) {
        this(context);
        this.callback = callback;
    }

    public MovableFloatingActionKey(Context context, boolean isSwipeKey) {
        this(context);
        this.isSwipeKey = isSwipeKey;
    }

    public MovableFloatingActionKey(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnTouchListener(this);

        LayoutParams mParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);

        this.setLayoutParams(mParams);
        key = new FloatingActionKey(getContext());
        key.setClickable(false);
        key.setLayoutParams(mParams);
        key.setElevation(1);

        closeButton = new FloatingActionKey(getContext());
        closeButton.setImageResource(android.R.drawable.ic_delete);
        closeButton.setElevation(2);
        closeButton.setOnClickListener(v -> {
            if (!isSwipeKey) {
                removeAllViews();
                key = null;
                if (callback != null) callback.onKeyRemoved(this);
            } else {
                setText(" ");
            }
        });
        closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        LayoutParams layoutParams = new LayoutParams(20, 20);
        layoutParams.gravity = Gravity.BOTTOM | Gravity.END;
        closeButton.setLayoutParams(layoutParams);

        addView(key);
        addView(closeButton);
    }

    public String getData(){
        return "KEY_" + getText() + " " + getX() + " " + getY();
    }

    public void setText(String s) {
        float scale = 1 + s.length() / 10f;
        setScaleX(scale);
        setScaleY(scale);

        if (s.length() > 3) {
            key.setText(s, 20 - s.length());
        } else key.setText(s, 30);
    }

    public String getText(){
        return key.key.toUpperCase();
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent){
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)view.getLayoutParams();
        int action = motionEvent.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                key.setButtonActive();
                dX = view.getX() - motionEvent.getRawX();
                dY = view.getY() - motionEvent.getRawY();
                return true; // Consumed
            }
            case MotionEvent.ACTION_UP: {
                key.setButtonInactive();
                return performClick();
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

                view.animate().x(newX).y(newY).setDuration(0).start();

                if (xyChangeListener != null)
                    xyChangeListener.onNewXY(newX, newY);

                return true; // Consumed
            }
            default:
                return super.onTouchEvent(motionEvent);
        }
    }
    public void setXyChangeListener(OnXyChangeListener xyChangeListener) {
        this.xyChangeListener = xyChangeListener;
        xyChangeListener.onNewXY(getX(), getY());
    }

    public interface OnXyChangeListener {
        void onNewXY(float x, float y);
    }
}