package xtr.keymapper.editor.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import xtr.keymapper.R;
import xtr.keymapper.databinding.CrosshairBinding;
import xtr.keymapper.databinding.MouseAimConfigBinding;
import xtr.keymapper.databinding.ResizableBinding;
import xtr.keymapper.editor.EditorUI;
import xtr.keymapper.editor.EditorUiComponent;
import xtr.keymapper.editor.EditorUiComponentCallback;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.floatingkeys.MovableFrameLayout;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.element.MouseAimConfig;

public class Crosshair extends EditorUiComponent {
    private final MovableFrameLayout crosshairView;
    private final MovableFloatingActionKey leftClick;

    public Crosshair(EditorUiComponentCallback callback, Context context, float x, float y) {
            super(callback, context, x, y);
            KeymapProfile profile = getCallback().getProfile();

            if (profile.mouseAimConfig == null)
                profile.mouseAimConfig = new MouseAimConfig();

            CrosshairBinding binding = CrosshairBinding.inflate(getLayoutInflater(), getCallback().getKeysContainerView(), true);
            crosshairView = binding.getRoot();

            binding.expandButton.setOnClickListener(this::onExpandButtonClicked);

            binding.editButton.setOnClickListener(v -> showMouseAimSettingsDialog());

            crosshairView.animate().x(x).y(y)
                    .setDuration(500)
                    .start();

            // Add left click
            leftClick = addLeftClick(profile.mouseAimConfig.xleftClick, profile.mouseAimConfig.yleftClick);

            /* Remove all views and release references when close button clicked */
            binding.closeButton.setOnClickListener(v -> getCallback().removeComponent(this, crosshairView, leftClick.frameView));
    }

    @Override
    public String getDataLine() {
        // Get x and y coordinates from view
        KeymapProfile profile = getCallback().getProfile();
        profile.mouseAimConfig.setCenterXY(crosshairView);
        profile.mouseAimConfig.setLeftClickXY(leftClick);
        return profile.mouseAimConfig.getData();
    }

    private void onExpandButtonClicked(View view) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext());
        CharSequence[] list = {"Limit to specified area", "Allow moving pointer out of screen"};
        // Set the dialog title
        builder.setTitle("Adjust bounds")
                .setItems(list, (dialog, which) -> {
                    KeymapProfile profile = getCallback().getProfile();
                    profile.mouseAimConfig.width = 0;
                    profile.mouseAimConfig.height = 0;
                    if (which == 0) {
                        profile.mouseAimConfig.limitedBounds = true;
                        // Delay for 100ms to skip layout changes
                        new Handler(Looper.getMainLooper()).postDelayed(ResizableArea::new, 100);
                    } else {
                        profile.mouseAimConfig.limitedBounds = false;
                    }
                });
        AlertDialog dialog = builder.create();
        if(getCallback().isOverlayOpen()) dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private MovableFloatingActionKey addLeftClick(float x, float y) {
        MovableFloatingActionKey leftClick = new MovableFloatingActionKey(getContext(), floatingActionKey -> {
        }, getCallback().getKeysContainerView());
        leftClick.frameView.setBackgroundResource(R.drawable.ic_baseline_mouse_36);
        leftClick.setText(R.string.left_click);
        leftClick.frameView.animate().x(x).y(y)
                .setDuration(500)
                .start();
        return leftClick;
    }

    public Crosshair(EditorUiComponentCallback mCallback, Context context, MouseAimConfig mouseAimConfig) {
        this(mCallback, context, mouseAimConfig.xCenter, mouseAimConfig.yCenter);
    }

    public void showMouseAimSettingsDialog() {
        KeymapConfig keymapConfig = new KeymapConfig(getContext());
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        MouseAimConfigBinding binding = MouseAimConfigBinding.inflate(getLayoutInflater(), null, false);

        // Load settings
        binding.rightClickCheckbox.setChecked(keymapConfig.rightClickMouseAim);
        binding.graveKeyCheckbox.setChecked(keymapConfig.keyGraveMouseAim);
        KeymapProfile profile = getCallback().getProfile();
        binding.applyNonLinearScalingCheckbox.setChecked(profile.mouseAimConfig.applyNonLinearScaling);
        binding.sliderXSensitivity.setValue(profile.mouseAimConfig.xSensitivity);
        binding.sliderYSensitivity.setValue(profile.mouseAimConfig.ySensitivity);

        View view = binding.getRoot();
        builder.setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    // Save settings
                    keymapConfig.rightClickMouseAim = binding.rightClickCheckbox.isChecked();
                    keymapConfig.keyGraveMouseAim = binding.graveKeyCheckbox.isChecked();
                    keymapConfig.applySharedPrefs();

                    // profile might have changed in the meantime
                    getCallback().getProfile().mouseAimConfig.applyNonLinearScaling = binding.applyNonLinearScalingCheckbox.isChecked();
                    getCallback().getProfile().mouseAimConfig.xSensitivity = binding.sliderXSensitivity.getValue();
                    getCallback().getProfile().mouseAimConfig.ySensitivity = binding.sliderYSensitivity.getValue();
                })
                .setNegativeButton(R.string.cancel, null);
        AlertDialog dialog = builder.create();
        if (getCallback().isOverlayOpen()) dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    public void animateLeftClick(float x, float y) {
        leftClick.frameView.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    class ResizableArea implements View.OnTouchListener, View.OnClickListener {
        private final ViewGroup rootView;
        private float defaultPivotX, defaultPivotY;

        @SuppressLint("ClickableViewAccessibility")
        public ResizableArea(){
            ResizableBinding binding1 = ResizableBinding.inflate(getLayoutInflater(), getCallback().getKeysContainerView(), true);
            rootView = binding1.getRoot();
            binding1.dragHandle.setOnTouchListener(this);
            binding1.saveButton.setOnClickListener(this);
            moveView();
        }

        private void getDefaultPivotXY(){
            defaultPivotX = rootView.getPivotX();
            defaultPivotY = rootView.getPivotY();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                EditorUI.resizeView(rootView, (int) event.getX(), (int) event.getY());
                // Resize View from center point
                if (defaultPivotX > 0) {
                    float newPivotX = rootView.getPivotX() - defaultPivotX;
                    float newPivotY = rootView.getPivotY() - defaultPivotY;
                    rootView.setX(rootView.getX() - newPivotX);
                    rootView.setY(rootView.getY() - newPivotY);
                }
                getDefaultPivotXY();
            } else
                v.performClick();
            return true;
        }
        @Override
        public void onClick(View v) {
            float x = rootView.getX() + rootView.getPivotX();
            float y = rootView.getY() + rootView.getPivotY();
            crosshairView.setX(x);
            crosshairView.setX(x);
            crosshairView.setY(y);
            getCallback().getProfile().mouseAimConfig.width = rootView.getPivotX();
            getCallback().getProfile().mouseAimConfig.height = rootView.getPivotY();

            getCallback().getKeysContainerView().removeView(rootView);
            rootView.invalidate();
        }
        private void moveView(){
            float x = crosshairView.getX() - crosshairView.getWidth();
            float y = crosshairView.getY() - crosshairView.getHeight();
            rootView.setX(x);
            rootView.setY(y);
        }
    }

}
