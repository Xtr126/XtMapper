package xtr.keymapper.editor.component;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import xtr.keymapper.R;
import xtr.keymapper.databinding.CameraBinding;
import xtr.keymapper.databinding.CameraConfigBinding;
import xtr.keymapper.editor.EditorUiComponent;
import xtr.keymapper.editor.EditorUiComponentCallback;
import xtr.keymapper.editor.SettingsOverlay;
import xtr.keymapper.keymap.KeymapProfile;

public class Camera extends EditorUiComponent {
    View cameraView;

    public Camera(EditorUiComponentCallback callback, Context context, float x, float y) {
        super(callback, context, x, y);

        CameraBinding binding = CameraBinding.inflate(getLayoutInflater(), getCallback().getKeysContainerView(), true);
        cameraView = binding.getRoot();


        binding.editButton.setOnClickListener(v -> showSettingsDialog());

        cameraView.animate().x(x).y(y)
                .setDuration(500)
                .start();


        /* Remove all views and release references when close button clicked */
        binding.closeButton.setOnClickListener(v -> getCallback().removeComponent(this, cameraView));

        KeymapProfile profile = getCallback().getProfile();
        if (profile.camera == null) profile.camera = new xtr.keymapper.keymap.element.Camera();
    }

    @Override
    public String getDataLine() {
        xtr.keymapper.keymap.element.Camera camera = getCallback().getProfile().camera;
        camera.x = cameraView.getX();
        camera.y = cameraView.getY();
        return camera.getData();
    }

    public void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        CameraConfigBinding binding = CameraConfigBinding.inflate(getLayoutInflater(), null, false);

        xtr.keymapper.keymap.element.Camera camera = getCamera();

        binding.key.setText(String.valueOf(camera.triggerKeyCode));

        binding.toggleSwitch.setChecked(camera.toggle);
        binding.sliderXSensitivity.setValue(camera.xSensitivity);
        binding.sliderYSensitivity.setValue(camera.ySensitivity);

        binding.key.setOnKeyListener(SettingsOverlay::onKey);

        View view = binding.getRoot();
        builder.setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    if(binding.key.getText().toString().isEmpty()) binding.key.setText(" ");
                    camera.triggerKeyCode = binding.key.getText().charAt(0);
                    camera.toggle = binding.toggleSwitch.isChecked();
                    camera.xSensitivity = binding.sliderXSensitivity.getValue();
                    camera.ySensitivity = binding.sliderYSensitivity.getValue();
                })
                .setNegativeButton(R.string.cancel, null);
        AlertDialog dialog = builder.create();
        if (getCallback().isOverlayOpen()) dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private xtr.keymapper.keymap.element.Camera getCamera() {
        xtr.keymapper.keymap.element.Camera camera = getCallback().getProfile().camera;
        return camera;
    }
}
