package xtr.keymapper.editor.component;

import static xtr.keymapper.editor.EditorUI.resizeView;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import xtr.keymapper.databinding.MouseWalkBinding;
import xtr.keymapper.editor.EditorUI;
import xtr.keymapper.editor.EditorUiComponent;
import xtr.keymapper.editor.EditorUiComponentCallback;
import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class MouseWalk extends EditorUiComponent {

    private MovableFrameLayout rootView;
    private xtr.keymapper.keymap.element.MouseWalk mouseWalk;
    private MouseWalkBinding binding;

    public MouseWalk(EditorUiComponentCallback callback, Context context, float defaultX, float defaultY) {
        super(callback, context, defaultX, defaultY);
        addMouseWalk(defaultX, defaultY);
    }

    public MouseWalk(EditorUiComponentCallback mCallback, Context context, xtr.keymapper.keymap.element.MouseWalk mouseWalk) {
        super(mCallback, context, mouseWalk.x, mouseWalk.y);
        addMouseWalk(mouseWalk);
    }

    private void addMouseWalk(xtr.keymapper.keymap.element.MouseWalk mouseWalk) {
        this.mouseWalk = mouseWalk;
        addMouseWalk(mouseWalk.x, mouseWalk.y);
    }

    @Override
    public String getDataLine() {
        // Create new instance for updating properties
        this.mouseWalk = new xtr.keymapper.keymap.element.MouseWalk(rootView);
        return xtr.keymapper.keymap.element.MouseWalk.TAG + " " +
                mouseWalk.x + " " +
                mouseWalk.y + " " +
                mouseWalk.radius;
    }


    private void addMouseWalk(float x, float y) {
        binding = MouseWalkBinding.inflate(getLayoutInflater(), getCallback().getKeysContainerView(), true);
        rootView = binding.getRoot();

        binding.closeButton.setOnClickListener(this::onClose);
        binding.resizeHandle.setOnTouchListener(new ResizeableDpadView(rootView));

        rootView.post(() -> {
            if (this.mouseWalk == null) {
                this.mouseWalk = new xtr.keymapper.keymap.element.MouseWalk(rootView);
            }
            moveResizeDpad(x, y);
        });
    }


    private void moveResizeDpad(float x, float y) {
        rootView.animate().x(x - mouseWalk.radius).y(y - mouseWalk.radius)
                .setDuration(500)
                .start();

        // resize dpad from saved profile configuration
        float x1 = (mouseWalk.radius*2) - rootView.getLayoutParams().width;
        float y1 = (mouseWalk.radius*2) - rootView.getLayoutParams().height;
        resizeView(rootView, (int) x1, (int) y1);
    }

    private void onClose(View v) {
        getCallback().removeComponent(this, rootView);
        rootView = null;
        binding = null;
    }

    static class ResizeableDpadView implements View.OnTouchListener {
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

}
