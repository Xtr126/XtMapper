package xtr.keymapper.fragment;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import xtr.keymapper.KeymapConfig;
import xtr.keymapper.R;
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
        binding.sliderMouse.setValue(keymapConfig.mouseSensitivity);
        binding.sliderScrollSpeed.setValue(keymapConfig.scrollSpeed);
        binding.sliderSwipeDelay.setValue(keymapConfig.swipeDelayMs);
        binding.inputDevice.setText(keymapConfig.device);

        binding.swipeDelayText.setText(getString(R.string.swipe_delay_ms, keymapConfig.swipeDelayMs));
        binding.sliderSwipeDelay.addOnChangeListener((slider, value, fromUser) -> binding.swipeDelayText.setText(getString(R.string.swipe_delay_ms, (int)value)));

        binding.mouseDragToggle.setChecked(keymapConfig.ctrlDragMouseGesture);
        binding.mouseWheelToggle.setChecked(keymapConfig.ctrlMouseWheelZoom);

        binding.mouseAimKeyGrave.setChecked(keymapConfig.keyGraveMouseAim);
        binding.mouseAimRightClick.setChecked(keymapConfig.rightClickMouseAim);

        loadKeyboardShortcuts();
        binding.launchEditor.setOnKeyListener(this::onKey);
        binding.stopService.setOnKeyListener(this::onKey);
        binding.switchProfile.setOnKeyListener(this::onKey);
        binding.mouseAimKey.setOnKeyListener(this::onKey);

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            setDefaultVisibilty();
            int itemId = item.getItemId();
            if (itemId == R.id.sliders) {
                binding.sliders.setVisibility(View.VISIBLE);
                return true;
            } else if (itemId == R.id.shortcuts) {
                binding.shortcuts.setVisibility(View.VISIBLE);
                return true;
            } else if (itemId == R.id.misc) {
                binding.misc.setVisibility(View.VISIBLE);
                return true;
            }
            return false;
        });
        setDefaultVisibilty();
        binding.sliders.setVisibility(View.VISIBLE);
    }

    private void setDefaultVisibilty() {
        binding.sliders.setVisibility(View.GONE);
        binding.misc.setVisibility(View.GONE);
        binding.shortcuts.setVisibility(View.GONE);
    }

    private void loadKeyboardShortcuts(){
        int stop_service = keymapConfig.stopServiceShortcutKey;
        int launch_editor = keymapConfig.launchEditorShortcutKey;
        int switch_profile = keymapConfig.switchProfileShortcutKey;
        int mouse_aim = keymapConfig.mouseAimShortcutKey;

        if (stop_service > -1) {
            String stopServiceShortcutKey = String.valueOf(Utils.alphabet.charAt(stop_service));
            binding.stopService.setText(stopServiceShortcutKey);
        }

        if (launch_editor > -1) {
            String launchEditorShortcutKey = String.valueOf(Utils.alphabet.charAt(launch_editor));
            binding.launchEditor.setText(launchEditorShortcutKey);
        }

        if (switch_profile > -1) {
            String switchProfileShortcutKey = String.valueOf(Utils.alphabet.charAt(switch_profile));
            binding.switchProfile.setText(switchProfileShortcutKey);
        }

        if (mouse_aim > -1) {
            String mouseAimShortcutKey = String.valueOf(Utils.alphabet.charAt(mouse_aim));
            binding.mouseAimKey.setText(mouseAimShortcutKey);
        }

        loadModifierKeys();
    }

    private void loadModifierKeys() {
        binding.launchEditorModifier.setText(keymapConfig.launchEditorShortcutKeyModifier);
        binding.stopServiceModifier.setText(keymapConfig.stopServiceShortcutKeyModifier);
        binding.switchProfileModifier.setText(keymapConfig.switchProfileShortcutKeyModifier);
        binding.mouseAimModifier.setText(keymapConfig.mouseAimShortcutKeyModifier);

        final String[] modifierKeys = {KeymapConfig.KEY_CTRL, KeymapConfig.KEY_ALT};
        ((MaterialAutoCompleteTextView)binding.launchEditorModifier).setSimpleItems(modifierKeys);
        ((MaterialAutoCompleteTextView)binding.stopServiceModifier).setSimpleItems(modifierKeys);
        ((MaterialAutoCompleteTextView)binding.switchProfileModifier).setSimpleItems(modifierKeys);
        ((MaterialAutoCompleteTextView)binding.mouseAimModifier).setSimpleItems(modifierKeys);
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
        if(binding.switchProfile.getText().toString().isEmpty()) binding.switchProfile.setText(" ");
        if(binding.mouseAimKey.getText().toString().isEmpty()) binding.mouseAimKey.setText(" ");

        int launch_editor_shortcut = Utils.alphabet.indexOf(binding.launchEditor.getText().charAt(0));
        int stop_service_shortcut = Utils.alphabet.indexOf(binding.stopService.getText().charAt(0));
        int switch_profile_shortcut = Utils.alphabet.indexOf(binding.switchProfile.getText().charAt(0));
        int mouse_aim_shortcut = Utils.alphabet.indexOf(binding.mouseAimKey.getText().charAt(0));

        keymapConfig.launchEditorShortcutKey = launch_editor_shortcut;
        keymapConfig.stopServiceShortcutKey = stop_service_shortcut;
        keymapConfig.switchProfileShortcutKey = switch_profile_shortcut;
        keymapConfig.mouseAimShortcutKey = mouse_aim_shortcut;

        keymapConfig.launchEditorShortcutKeyModifier = binding.launchEditorModifier.getText().toString();
        keymapConfig.stopServiceShortcutKeyModifier = binding.stopServiceModifier.getText().toString();
        keymapConfig.switchProfileShortcutKeyModifier = binding.switchProfileModifier.getText().toString();
        keymapConfig.mouseAimShortcutKeyModifier = binding.mouseAimModifier.getText().toString();
    }

    @Override
    public void onDestroyView() {
        saveKeyboardShortcuts();
        // Split the string to allow only one string without whitespaces
        String[] device = binding.inputDevice.getText().toString().split("\\s+");
        keymapConfig.device = device[0];

        keymapConfig.mouseSensitivity = binding.sliderMouse.getValue();
        keymapConfig.scrollSpeed = binding.sliderScrollSpeed.getValue();
        keymapConfig.swipeDelayMs = (int) binding.sliderSwipeDelay.getValue();

        keymapConfig.ctrlMouseWheelZoom = binding.mouseWheelToggle.isChecked();
        keymapConfig.ctrlDragMouseGesture = binding.mouseDragToggle.isChecked();

        keymapConfig.rightClickMouseAim = binding.mouseAimRightClick.isChecked();
        keymapConfig.keyGraveMouseAim = binding.mouseAimKeyGrave.isChecked();

        dpadConfig.setDpadRadiusMultiplier(binding.sliderDpad.getValue());

        keymapConfig.applySharedPrefs();
        // Reload keymap if remote service running
        InputService.reloadKeymap();
        binding = null;
        super.onDestroyView();
    }
}
