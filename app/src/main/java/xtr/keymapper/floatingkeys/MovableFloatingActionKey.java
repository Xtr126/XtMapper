package xtr.keymapper.floatingkeys;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;

import xtr.keymapper.databinding.FloatingKeyBinding;

public class MovableFloatingActionKey implements View.OnTouchListener {

    private float dX, dY;
    public final TextView textView;
    public final FrameLayout frameView;
    private OnXyChangeListener xyChangeListener;
    public final MaterialButton closeButton;
    public final FloatingKeyBinding binding;
    private OnKeyRemoved callback;
    boolean isSwipeKey = false;

    public interface OnKeyRemoved {

        void onKeyRemoved(MovableFloatingActionKey key);
    }
    public MovableFloatingActionKey(Context context) {

        binding = FloatingKeyBinding.inflate(LayoutInflater.from(context));
        frameView = binding.getRoot();
        frameView.setOnTouchListener(this);

        closeButton = binding.closeButton;
        textView = binding.textView;

        closeButton.setOnClickListener(v -> {
            if (!isSwipeKey) {
                frameView.removeAllViews();
                frameView.invalidate();
                if (callback != null) callback.onKeyRemoved(this);
            } else {
                setText(" ");
            }
        });
    }

    public MovableFloatingActionKey(Context context, OnKeyRemoved callback) {
        this(context);
        this.callback = callback;
    }
    public MovableFloatingActionKey(Context context, boolean isSwipeKey) {
        this(context);
        this.isSwipeKey = isSwipeKey;
    }

    public String getData(){
        return "KEY_" + getText() + " " + frameView.getX() + " " + frameView.getY() + " " + frameView.getPivotX();
    }

    public void setText(CharSequence s) {
        textView.setText(s);
    }

    public void setText(@StringRes int resId) {
        textView.setText(resId);
    }

    public String getText(){
        return textView.getText().toString().toUpperCase();
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
            case MotionEvent.ACTION_UP: {
                return frameView.performClick();
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
                return frameView.onTouchEvent(motionEvent);
        }
    }
    public void setXyChangeListener(OnXyChangeListener xyChangeListener) {
        this.xyChangeListener = xyChangeListener;
        xyChangeListener.onNewXY(frameView.getX(), frameView.getY());
    }

    public interface OnXyChangeListener {
        void onNewXY(float x, float y);
    }

    public void setX(float x) {
        frameView.setX(x);
    }

    public void setY(float y) {
        frameView.setY(y);
    }

    public float getX() {
        return frameView.getX();
    }

    public float getY() {
        return frameView.getY();
    }

    public void setOnClickListener(View.OnClickListener listener) {
        frameView.setOnClickListener(listener);
    }
}