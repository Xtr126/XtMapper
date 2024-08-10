package xtr.keymapper.editor;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

class ResizeableDpadView implements View.OnTouchListener {
    final View rootView;
    float defaultPivotX, defaultPivotY;

    public ResizeableDpadView(View rootView) {
        this.rootView = rootView;
    }

    private void getDefaultPivotXY() {
        defaultPivotX = rootView.getPivotX();
        defaultPivotY = rootView.getPivotY();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            // Resize View in fixed ratio
            int newSize = ((int)event.getX() + (int)event.getY()) / 2;
            EditorUI.resizeView(rootView, newSize, newSize);

            // Resize View from center point
            if (defaultPivotX > 0) {
                float newPivotX = rootView.getPivotX() - defaultPivotX;
                float newPivotY = rootView.getPivotY() - defaultPivotY;
                rootView.setX(rootView.getX() - newPivotX);
                rootView.setY(rootView.getY() - newPivotY);
            }
            getDefaultPivotXY();
        }
        return v.onTouchEvent(event);
    }
}
