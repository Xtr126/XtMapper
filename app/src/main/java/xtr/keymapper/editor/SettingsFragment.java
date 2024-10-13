package xtr.keymapper.editor;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import xtr.keymapper.R;
import xtr.keymapper.Utils;
import xtr.keymapper.databinding.KeymapEditorItemBinding;
import xtr.keymapper.databinding.KeymapEditorLayoutBinding;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.server.RemoteServiceHelper;

public class SettingsFragment {
    private final KeymapConfig keymapConfig;
    private KeymapEditorLayoutBinding binding;
    private Map<String, Integer> pointerModeMap;
    private Map<String, Integer> touchpadInputModeMap;
    private final Context context;
    public SettingsFragment(Context context) {
        this.context = context;
        keymapConfig = new KeymapConfig(context);
    }

    public ViewGroup createView(@NonNull LayoutInflater inflater) {
        // Inflate the layout for this fragment
        binding = KeymapEditorLayoutBinding.inflate(inflater);
        return binding.getRoot();
    }


    private final MaterialButtonToggleGroup.OnButtonCheckedListener ON_BUTTON_CHECKED_LISTENER = new MaterialButtonToggleGroup.OnButtonCheckedListener() {
        @Override
        public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
            if (checkedId == R.id.button_sliders) {
                binding.sliders.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            } else if (checkedId == R.id.button_shortcuts) {
                binding.shortcuts.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            } else if (checkedId == R.id.button_misc) {
                binding.misc.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            } else if (checkedId == R.id.button_add) {
                binding.catalog.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        }
    };

    public void init(int startMode) {
        binding.sliderMouse.setValue(keymapConfig.mouseSensitivity);
        binding.sliderScrollSpeed.setValue(keymapConfig.scrollSpeed);
        binding.sliderSwipeDelay.setValue(keymapConfig.swipeDelayMs);

        binding.swipeDelayText.setText(context.getString(R.string.swipe_delay_ms, keymapConfig.swipeDelayMs));
        binding.sliderSwipeDelay.addOnChangeListener((slider, value, fromUser) -> binding.swipeDelayText.setText(context.getString(R.string.swipe_delay_ms, (int)value)));

        binding.mouseDragToggle.setChecked(keymapConfig.ctrlDragMouseGesture);
        binding.mouseWheelToggle.setChecked(keymapConfig.ctrlMouseWheelZoom);

        binding.mouseAimKeyGrave.setChecked(keymapConfig.keyGraveMouseAim);
        binding.mouseAimRightClick.setChecked(keymapConfig.rightClickMouseAim);
        binding.autoProfileSwitch.setChecked(keymapConfig.disableAutoProfiling);
        binding.useShizuku.setChecked(keymapConfig.useShizuku);
        binding.editorOverlay.setChecked(keymapConfig.editorOverlay);

        loadKeyboardShortcuts();
        binding.launchEditor.setOnKeyListener(this::onKey);
        binding.pauseResume.setOnKeyListener(this::onKey);
        binding.switchProfile.setOnKeyListener(this::onKey);
        binding.mouseAimKey.setOnKeyListener(this::onKey);



        mouseAimActions();
        loadTouchpadInputSettings();

        final int[] pointerModeCodes = {KeymapConfig.POINTER_COMBINED, KeymapConfig.POINTER_OVERLAY, KeymapConfig.POINTER_SYSTEM};
        String[] pointerModeNames = context.getResources().getStringArray(R.array.pointer_modes);
        pointerModeMap = IntStream.range(0, pointerModeCodes.length)
                .boxed()
                .collect(Collectors.toMap(k -> pointerModeNames[k], v -> pointerModeCodes[v]));

        for (Map.Entry<String, Integer> entry : pointerModeMap.entrySet()) {
            if (entry.getValue().equals(keymapConfig.pointerMode)) {
                binding.pointerMode.setText(entry.getKey());
            }
        }
        binding.pointerMode.setSimpleItems(pointerModeNames);

        binding.toggleButtonGroup.addOnButtonCheckedListener(ON_BUTTON_CHECKED_LISTENER);

        if (startMode == EditorUI.START_EDITOR) {
            binding.sliders.setVisibility(View.GONE);
            binding.shortcuts.setVisibility(View.GONE);
            binding.misc.setVisibility(View.GONE);
            binding.catalog.setVisibility(View.VISIBLE);
        }
        binding.buttonMisc.setChecked(binding.misc.getVisibility() == View.VISIBLE);
        binding.buttonShortcuts.setChecked(binding.shortcuts.getVisibility() == View.VISIBLE);
        binding.buttonSliders.setChecked(binding.sliders.getVisibility() == View.VISIBLE);
        binding.buttonAdd.setChecked(binding.catalog.getVisibility() == View.VISIBLE);
    }


    private void loadKeyboardShortcuts(){
        int pause_resume = keymapConfig.pauseResumeShortcutKey;
        int launch_editor = keymapConfig.launchEditorShortcutKey;
        int switch_profile = keymapConfig.switchProfileShortcutKey;
        int mouse_aim = keymapConfig.mouseAimShortcutKey;

        if (pause_resume > -1) {
            String pauseResumeShortcutKey = String.valueOf(Utils.alphabet.charAt(pause_resume));
            binding.pauseResume.setText(pauseResumeShortcutKey);
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
        binding.pauseResumeModifier.setText(keymapConfig.pauseResumeShortcutKeyModifier);
        binding.switchProfileModifier.setText(keymapConfig.switchProfileShortcutKeyModifier);

        final String[] modifierKeys = {KeymapConfig.KEY_CTRL, KeymapConfig.KEY_ALT};
        binding.launchEditorModifier.setSimpleItems(modifierKeys);
        binding.pauseResumeModifier.setSimpleItems(modifierKeys);
        binding.switchProfileModifier.setSimpleItems(modifierKeys);
    }

    private void mouseAimActions() {
        if (keymapConfig.mouseAimToggle) binding.mouseAimAction.setText(R.string.toggle);
        else binding.mouseAimAction.setText(R.string.hold);

        String[] mouseAimActionNames = context.getResources().getStringArray(R.array.mouse_aim_actions);
        binding.mouseAimAction.setSimpleItems(mouseAimActionNames);
    }

    private void loadTouchpadInputSettings() {
        final int[] touchpadInputModeCodes = {KeymapConfig.TOUCHPAD_DIRECT, KeymapConfig.TOUCHPAD_RELATIVE, KeymapConfig.TOUCHPAD_DISABLED};
        String[] touchpadInputModeNames = context.getResources().getStringArray(R.array.touchpad_input_modes);
        touchpadInputModeMap = IntStream.range(0, touchpadInputModeCodes.length)
                .boxed()
                .collect(Collectors.toMap(k -> touchpadInputModeNames[k], v -> touchpadInputModeCodes[v]));

        for (Map.Entry<String, Integer> entry : touchpadInputModeMap.entrySet()) {
            if (entry.getValue().equals(keymapConfig.touchpadInputMode)) {
                binding.touchpadInputMode.setText(entry.getKey());
            }
        }
        binding.touchpadInputMode.setSimpleItems(touchpadInputModeNames);
    }

    public boolean onKey(View view, int keyCode, KeyEvent event) {
        String key = String.valueOf(event.getDisplayLabel());
        if ( key.matches("[a-zA-Z0-9]+" )) ((EditText) view).setText(key);
        else ((EditText) view).getText().clear();
        return true;
    }


    private void saveKeyboardShortcuts() {
        if(binding.launchEditor.getText().toString().isEmpty()) binding.launchEditor.setText(" ");
        if(binding.pauseResume.getText().toString().isEmpty()) binding.pauseResume.setText(" ");
        if(binding.switchProfile.getText().toString().isEmpty()) binding.switchProfile.setText(" ");
        if(binding.mouseAimKey.getText().toString().isEmpty()) binding.mouseAimKey.setText(" ");

        int launch_editor_shortcut = Utils.alphabet.indexOf(binding.launchEditor.getText().charAt(0));
        int pause_resume_shortcut = Utils.alphabet.indexOf(binding.pauseResume.getText().charAt(0));
        int switch_profile_shortcut = Utils.alphabet.indexOf(binding.switchProfile.getText().charAt(0));
        int mouse_aim_shortcut = Utils.alphabet.indexOf(binding.mouseAimKey.getText().charAt(0));

        keymapConfig.launchEditorShortcutKey = launch_editor_shortcut;
        keymapConfig.pauseResumeShortcutKey = pause_resume_shortcut;
        keymapConfig.switchProfileShortcutKey = switch_profile_shortcut;
        keymapConfig.mouseAimShortcutKey = mouse_aim_shortcut;

        keymapConfig.launchEditorShortcutKeyModifier = binding.launchEditorModifier.getText().toString();
        keymapConfig.pauseResumeShortcutKeyModifier = binding.pauseResumeModifier.getText().toString();
        keymapConfig.switchProfileShortcutKeyModifier = binding.switchProfileModifier.getText().toString();
    }

    public void onDestroyView() {
        saveKeyboardShortcuts();
        keymapConfig.mouseAimToggle = binding.mouseAimAction.getText().toString().equals(context.getResources().getString(R.string.toggle));
        keymapConfig.touchpadInputMode = touchpadInputModeMap.get(binding.touchpadInputMode.getText().toString());
        keymapConfig.pointerMode = pointerModeMap.get(binding.pointerMode.getText().toString());

        keymapConfig.mouseSensitivity = binding.sliderMouse.getValue();
        keymapConfig.scrollSpeed = binding.sliderScrollSpeed.getValue();
        keymapConfig.swipeDelayMs = (int) binding.sliderSwipeDelay.getValue();

        keymapConfig.ctrlMouseWheelZoom = binding.mouseWheelToggle.isChecked();
        keymapConfig.ctrlDragMouseGesture = binding.mouseDragToggle.isChecked();

        keymapConfig.rightClickMouseAim = binding.mouseAimRightClick.isChecked();
        keymapConfig.keyGraveMouseAim = binding.mouseAimKeyGrave.isChecked();
        keymapConfig.disableAutoProfiling = binding.autoProfileSwitch.isChecked();
        keymapConfig.useShizuku = binding.useShizuku.isChecked();
        keymapConfig.editorOverlay = binding.editorOverlay.isChecked();

        keymapConfig.applySharedPrefs();
        binding = null;
    }

    public void inflate(@MenuRes int menuRes, int startMode, LayoutInflater layoutInflater) {
        PopupMenu popupMenu = new PopupMenu(context, new View(context));
        popupMenu.inflate(menuRes);
        Menu menu = popupMenu.getMenu();

        if (startMode == EditorUI.START_EDITOR) for (int n = 1; n <= 3; n++) { // n = { 1,2,3 } For dividing between three columns
            for (int i = menu.size()*(n-1)/3; i < menu.size()*n/3; i++) { // i = { 0,1,2.. }
                MenuItem menuItem = menu.getItem(i);

                LinearLayout parentView;
                if ( n == 1 ) {
                    parentView = binding.L1;
                } else if ( n == 2 ) {
                    parentView = binding.L2;
                } else {
                    parentView = binding.L3;
                }

                KeymapEditorItemBinding itemBinding = KeymapEditorItemBinding.inflate(layoutInflater, parentView, true);
                itemBinding.imageView.setImageDrawable(menuItem.getIcon());
                itemBinding.title.setText(menuItem.getTitle());
                itemBinding.description.setText(menuItem.getContentDescription());
            }
        } else if (startMode == EditorUI.START_SETTINGS) {
            KeymapEditorItemBinding itemBinding = KeymapEditorItemBinding.inflate(layoutInflater, binding.L1, true);
            itemBinding.imageView.setImageResource(R.drawable.ic_baseline_done_36);
            itemBinding.title.setText(R.string.save);
        }

    }
}
