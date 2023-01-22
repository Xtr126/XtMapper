package xtr.keymapper.fragment;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import xtr.keymapper.EditorUI;
import xtr.keymapper.KeymapConfig;
import xtr.keymapper.Utils;
import xtr.keymapper.databinding.FragmentSettingsDialogBinding;
import xtr.keymapper.dpad.DpadConfig;
import xtr.keymapper.server.InputService;

public class SettingsFragment extends BottomSheetDialogFragment {
    private final DpadConfig dpadConfig;
    private final KeymapConfig keymapConfig;
    private FragmentSettingsDialogBinding binding;

    public SettingsFragment(Context context) {
        dpadConfig = new DpadConfig(context);
        keymapConfig = new KeymapConfig(context);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentSettingsDialogBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.sliderDpad.setValue(dpadConfig.getDpadRadiusMultiplier());
        binding.sliderMouse.setValue(keymapConfig.getMouseSensitivity());
        binding.sliderScrollSpeed.setValue(keymapConfig.getScrollSpeed());
        binding.inputDevice.setText(keymapConfig.getDevice());

        loadKeyboardShortcuts();
        binding.launchEditor.setOnKeyListener(this::onKey);
        binding.stopService.setOnKeyListener(this::onKey);
    }

    private void loadKeyboardShortcuts(){
        int stop_service = keymapConfig.getStopServiceShortcutKey();
        int launch_editor = keymapConfig.getLaunchEditorShortcutKey();

        if (stop_service > -1) {
            String stopServiceShortcutKey = String.valueOf(Utils.alphabet.charAt(stop_service));
            binding.stopService.setText(stopServiceShortcutKey);
        }

        if (launch_editor > -1) {
            String launchEditorShortcutKey = String.valueOf(Utils.alphabet.charAt(launch_editor));
            binding.launchEditor.setText(launchEditorShortcutKey);
        }
    }

    public boolean onKey(View view, int keyCode, KeyEvent event) {
        String key = String.valueOf(event.getDisplayLabel());
        if ( key.matches("[a-zA-Z0-9]+" )) ((EditText) view).setText(key);
        else ((EditText) view).getText().clear();
        return true;
    }

    @NonNull @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        // Expanded bottom sheet dialog by default
        dialog.setOnShowListener(d -> ((BottomSheetDialog) d).getBehavior().setState(STATE_EXPANDED));
        return dialog;
    }

    private void saveKeyboardShortcuts() {
        if(binding.launchEditor.getText().toString().isEmpty()) binding.launchEditor.setText(" ");
        if(binding.stopService.getText().toString().isEmpty()) binding.stopService.setText(" ");

        int launch_editor_shortcut = Utils.alphabet.indexOf(binding.launchEditor.getText().charAt(0));
        int stop_service_shortcut = Utils.alphabet.indexOf(binding.stopService.getText().charAt(0));
        keymapConfig.setLaunchEditorShortcutKey(launch_editor_shortcut);
        keymapConfig.setStopServiceShortcutKey(stop_service_shortcut);
    }

    @Override
    public void onDestroyView() {
        // Split the string to allow only one string without whitespaces
        String[] device = binding.inputDevice.getText().toString().split("\\s+");
        keymapConfig.setDevice(device[0]);

        keymapConfig.setMouseSensitivity(binding.sliderMouse.getValue());
        keymapConfig.setScrollSpeed(binding.sliderScrollSpeed.getValue());
        dpadConfig.setDpadRadiusMultiplier(binding.sliderDpad.getValue());
        saveKeyboardShortcuts();

        InputService.reloadKeymap();
        binding = null;
        super.onDestroyView();
    }
}