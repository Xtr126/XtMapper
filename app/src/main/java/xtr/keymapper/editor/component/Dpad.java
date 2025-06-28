package xtr.keymapper.editor.component;

import static xtr.keymapper.editor.EditorUI.resizeView;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import xtr.keymapper.databinding.DpadArrowsBinding;
import xtr.keymapper.databinding.DpadBinding;
import xtr.keymapper.dpad.DpadKeyCodes;
import xtr.keymapper.editor.EditorUI;
import xtr.keymapper.editor.EditorUiComponent;
import xtr.keymapper.editor.EditorUiComponentCallback;
import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class Dpad extends EditorUiComponent {

    private final float defaultX;
    private final float defaultY;
    private MovableFrameLayout dpadView;
    private xtr.keymapper.dpad.Dpad dpad;
    private DpadBinding dpadBinding;

    public Dpad(EditorUiComponentCallback callback, Context context, float defaultX, float defaultY) {
        super(callback, context, defaultX, defaultY);
        this.defaultX = defaultX;
        this.defaultY = defaultY;
    }

    public Dpad(EditorUiComponentCallback mCallback, Context context, xtr.keymapper.dpad.Dpad dpad) {
        this(mCallback, context, dpad.getX(), dpad.getY());
        addDpad(dpad);
    }

    private void addDpad(xtr.keymapper.dpad.Dpad dpad) {
        this.dpad = dpad;
        if (dpad.keycodes.equals(new DpadKeyCodes(xtr.keymapper.dpad.Dpad.ARROW_KEYS)))
            addArrowKeysDpad(dpad.getX(), dpad.getY());
        else
            addDpad(dpad.getX(), dpad.getY(), dpad.keycodes);
    }

    @Override
    public String getDataLine() {
        // Create new dpad instance for updating properties
        if (dpad.keycodes.equals(new DpadKeyCodes(xtr.keymapper.dpad.Dpad.ARROW_KEYS)))
            this.dpad = new xtr.keymapper.dpad.Dpad(dpadView, new DpadKeyCodes(xtr.keymapper.dpad.Dpad.ARROW_KEYS), xtr.keymapper.dpad.Dpad.TAG_ARROW_KEYS);
        else
            this.dpad = new xtr.keymapper.dpad.Dpad(dpadView, new DpadKeyCodes(dpadBinding), xtr.keymapper.dpad.Dpad.TAG);

        return dpad.getData();
    }

    public Dpad pickType() {
        final CharSequence[] items = { "Arrow keys", "WASD Keys", "Custom"};
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setTitle("Select Dpad").setItems(items, (dialog, i) -> {
            if (i == 0) addArrowKeysDpad(defaultX, defaultY);
            else if (i == 1) addWasdDpad(defaultX, defaultY);
            else addCustomDpad(defaultX, defaultY);
        });
        AlertDialog dialog = builder.create();
        if (getCallback().isOverlayOpen()) dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
        return this;
    }

    private void addArrowKeysDpad(float x, float y) {
        DpadArrowsBinding dpadArrowsBinding = DpadArrowsBinding.inflate(getLayoutInflater(), getCallback().getKeysContainerView(), true);
        dpadView = dpadArrowsBinding.getRoot();

        dpadArrowsBinding.closeButton.setOnClickListener(this::onClose);
        dpadArrowsBinding.resizeHandle.setOnTouchListener(new ResizeableDpadView(dpadView));

        if (dpad == null)
            this.dpad = new xtr.keymapper.dpad.Dpad(dpadView, new DpadKeyCodes(xtr.keymapper.dpad.Dpad.ARROW_KEYS), xtr.keymapper.dpad.Dpad.TAG_ARROW_KEYS);

        moveResizeDpad(x, y);
    }

    private void addDpad(float x, float y, DpadKeyCodes dpadKeycodes) {
        dpadBinding = DpadBinding.inflate(getLayoutInflater(), getCallback().getKeysContainerView(), true);
        dpadView = dpadBinding.getRoot();

        dpadBinding.closeButton.setOnClickListener(this::onClose);
        dpadBinding.resizeHandle.setOnTouchListener(new ResizeableDpadView(dpadView));

        if (this.dpad == null) {
            setDpadKeys(dpadBinding, dpadKeycodes);
            this.dpad = new xtr.keymapper.dpad.Dpad(dpadView, new DpadKeyCodes(dpadBinding), xtr.keymapper.dpad.Dpad.TAG);
        }

        moveResizeDpad(x, y);
    }

    private void addWasdDpad(float x, float y) {
        addDpad(x, y, new DpadKeyCodes(xtr.keymapper.dpad.Dpad.WASD_KEYS));
    }

    private void addCustomDpad(float x, float y) {
        addDpad(x, y, new DpadKeyCodes(xtr.keymapper.dpad.Dpad.IJKL_KEYS));
    }

    private void setDpadKeys(DpadBinding binding, DpadKeyCodes dpadKeycodes) {
        // strip KEY_
        binding.keyUp.setText(dpadKeycodes.Up.substring(4));
        binding.keyDown.setText(dpadKeycodes.Down.substring(4));
        binding.keyLeft.setText(dpadKeycodes.Left.substring(4));
        binding.keyRight.setText(dpadKeycodes.Right.substring(4));
        for (TextView key : new TextView[]{binding.keyUp, binding.keyDown, binding.keyRight, binding.keyLeft}) {
            key.setOnClickListener(
                    view -> getCallback().setOnKeyListener(k -> ((TextView)view).setText(k)));
        }
    }

    private void moveResizeDpad(float x, float y) {
        dpadView.animate().x(x).y(y)
                .setDuration(500)
                .start();

        // resize dpad from saved profile configuration
        float x1 = dpad.getWidth() - dpadView.getLayoutParams().width;
        float y1 = dpad.getHeight() - dpadView.getLayoutParams().height;
        resizeView(dpadView, (int) x1, (int) y1);
    }

    private void onClose(View v) {
        getCallback().removeComponent(this, dpadView);
        dpadView = null;
        dpadBinding = null;
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
