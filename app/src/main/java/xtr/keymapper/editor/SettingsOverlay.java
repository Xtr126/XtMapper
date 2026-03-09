package xtr.keymapper.editor;

import android.content.Context;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuItemCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import xtr.keymapper.R;
import xtr.keymapper.databinding.KeymapEditorItemBinding;
import xtr.keymapper.databinding.KeymapEditorLayoutBinding;
import xtr.keymapper.databinding.SettingsBinding;
import xtr.keymapper.keymap.KeymapConfig;

public class SettingsOverlay {
    private final KeymapConfig keymapConfig;
    protected KeymapEditorLayoutBinding binding;
    private Map<String, Integer> pointerModeMap;
    private Map<String, Integer> touchpadInputModeMap;
    private final Context context;
    private OnCardItemSelectedListener onCardItemSelectedListener;
    private final int startMode;
    boolean overlayWindow;
    private View keyInFocus;

    public SettingsOverlay(Context context, int startMode) {
        this.context = context;
        keymapConfig = new KeymapConfig(context);
        this.startMode = startMode;
    }

    public ViewGroup createView(@NonNull LayoutInflater inflater) {
        // Inflate the layout for this fragment
        binding = KeymapEditorLayoutBinding.inflate(inflater);
        init();
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
                if (EditorUI.START_SETTINGS == startMode)
                    onCardItemSelected(R.id.save);
                else binding.catalog.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        }
    };

    private void init() {
        binding.sliderMouse.setValue(keymapConfig.mouseSensitivity);
        binding.sliderScrollSpeed.setValue(keymapConfig.scrollSpeed);
        binding.sliderSwipeDelay.setValue(keymapConfig.swipeDelayMs);
        binding.sliderFloatingKeysSize.setValue(keymapConfig.floatingKeysSize);

        binding.swipeDelayText.setText(context.getString(R.string.swipe_delay_ms, keymapConfig.swipeDelayMs));
        binding.sliderSwipeDelay.addOnChangeListener((slider, value, fromUser) -> binding.swipeDelayText.setText(context.getString(R.string.swipe_delay_ms, (int)value)));

        binding.mouseDragToggle.setChecked(keymapConfig.ctrlDragMouseGesture);
        binding.mouseWheelToggle.setChecked(keymapConfig.ctrlMouseWheelZoom);

        binding.mouseAimKeyGrave.setChecked(keymapConfig.keyGraveMouseAim);
        binding.mouseAimRightClick.setChecked(keymapConfig.rightClickMouseAim);

        loadKeyboardShortcuts();
        binding.launchEditor.setOnKeyListener(SettingsOverlay::onKey);
        binding.pauseResume.setOnKeyListener(SettingsOverlay::onKey);
        binding.switchProfile.setOnKeyListener(SettingsOverlay::onKey);
        binding.mouseAimKey.setOnKeyListener(SettingsOverlay::onKey);

        binding.mouseAimKey.setOnGenericMotionListener((v, event) -> {
            // Handle middle mouse button
            if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                if (event.getAction() == MotionEvent.ACTION_BUTTON_PRESS) {
                    if (event.getActionButton() == MotionEvent.BUTTON_TERTIARY) {
                        // Middle mouse button was pressed
                        ((EditText) v).setText("MMB");
                        return true;
                    }
                }
            }
            return false; // Event not handled
        });


        mouseAimActions();
        loadTouchpadInputSettings();

        int[] pointerModeCodes = {KeymapConfig.POINTER_COMBINED, KeymapConfig.POINTER_OVERLAY, KeymapConfig.POINTER_SYSTEM};
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

        binding.advanced.setOnClickListener(v -> showSettingsDialog());
    }

    private void showSettingsDialog() {

        SettingsBinding settingsBinding = SettingsBinding.inflate(LayoutInflater.from(context));

        settingsBinding.autoProfileSwitch.setChecked(keymapConfig.disableAutoProfiling);
        settingsBinding.useShizuku.setChecked(keymapConfig.useShizuku);
        settingsBinding.editorOverlay.setChecked(keymapConfig.editorOverlay);

        settingsBinding.showControls.setChecked(keymapConfig.showControls);
        settingsBinding.showControlsOpacity.setValue(keymapConfig.showControlsOpacity);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.MaterialAlertDialog_Centered)
                .setView(settingsBinding.getRoot())
                .setTitle(R.string.advanced)
                .setIcon(R.drawable.ic_baseline_settings_24)
                .setCancelable(true)
                .setOnCancelListener(d -> {
                    keymapConfig.disableAutoProfiling = settingsBinding.autoProfileSwitch.isChecked();
                    keymapConfig.useShizuku = settingsBinding.useShizuku.isChecked();
                    keymapConfig.editorOverlay = settingsBinding.editorOverlay.isChecked();
                    keymapConfig.showControls = settingsBinding.showControls.isChecked();
                    keymapConfig.showControlsOpacity = settingsBinding.showControlsOpacity.getValue();
                })
                .create();
        if(overlayWindow) dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }


    private void loadKeyboardShortcuts(){
        // Strips KEY_ from start
        Function<String, String> removekeyPrefix = key -> key.length() > 4 ? key.substring(4) :  " ";

        binding.pauseResume.setText(removekeyPrefix.apply(keymapConfig.pauseResumeShortcutKey));
        binding.launchEditor.setText(removekeyPrefix.apply(keymapConfig.launchEditorShortcutKey));
        binding.switchProfile.setText(removekeyPrefix.apply(keymapConfig.switchProfileShortcutKey));
        binding.mouseAimKey.setText(removekeyPrefix.apply(keymapConfig.mouseAimShortcutKey));

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


    private void saveKeyboardShortcuts() {
        Function<EditText, String> keyPrefix = e -> "KEY_" + e.getText();

        keymapConfig.launchEditorShortcutKey = keyPrefix.apply(binding.launchEditor);
        keymapConfig.pauseResumeShortcutKey = keyPrefix.apply(binding.pauseResume);
        keymapConfig.switchProfileShortcutKey = keyPrefix.apply(binding.switchProfile);
        keymapConfig.mouseAimShortcutKey = keyPrefix.apply(binding.mouseAimKey);

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
        keymapConfig.floatingKeysSize = binding.sliderFloatingKeysSize.getValue();

        keymapConfig.ctrlMouseWheelZoom = binding.mouseWheelToggle.isChecked();
        keymapConfig.ctrlDragMouseGesture = binding.mouseDragToggle.isChecked();

        keymapConfig.rightClickMouseAim = binding.mouseAimRightClick.isChecked();
        keymapConfig.keyGraveMouseAim = binding.mouseAimKeyGrave.isChecked();

        keymapConfig.applySharedPrefs();
        binding = null;
    }

    public void inflateMenuResource(int startMode, LayoutInflater layoutInflater) {
        PopupMenu popupMenu = new PopupMenu(context, new View(context));
        popupMenu.inflate(R.menu.keymap_editor_menu);
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
                MaterialButton button = itemBinding.getRoot();
                button.setIcon(menuItem.getIcon());
                button.setText(menuItem.getTitle());
                button.setContentDescription(MenuItemCompat.getContentDescription(menuItem));
                button.setOnClickListener(v -> onCardItemSelected(menuItem.getItemId()));
            }
        } else if (startMode == EditorUI.START_SETTINGS) {
            binding.buttonAdd.setIconResource(R.drawable.ic_baseline_done_36);
        }

    }

    void hideButtons() {
        binding.L3.setVisibility(View.GONE);
        binding.L2.setVisibility(View.GONE);
        binding.L1.setVisibility(View.GONE);
    }

    void unHideButtons() {
        binding.L3.setVisibility(View.VISIBLE);
        binding.L2.setVisibility(View.VISIBLE);
        binding.L1.setVisibility(View.VISIBLE);
    }

    private void onCardItemSelected(int itemId) {
        if (onCardItemSelectedListener != null)
            onCardItemSelectedListener.onActionSelected(itemId);
    }

    public void setOnActionSelectedListener(OnCardItemSelectedListener l) {
        this.onCardItemSelectedListener = l;
    }

    public void onRegisterKeyEventListener() {
        binding.launchEditor.setOnKeyListener(this::focusKey);
        binding.pauseResume.setOnKeyListener(this::focusKey);
        binding.switchProfile.setOnKeyListener(this::focusKey);
        binding.mouseAimKey.setOnKeyListener(this::focusKey);
    }

    public void onUnRegisterKeyEventListener() {
        // Accept key events from View
        binding.launchEditor.setOnKeyListener(SettingsOverlay::onKey);
        binding.pauseResume.setOnKeyListener(SettingsOverlay::onKey);
        binding.switchProfile.setOnKeyListener(SettingsOverlay::onKey);
        binding.mouseAimKey.setOnKeyListener(SettingsOverlay::onKey);
    }

    public static boolean onKey(View view, int keyCode, KeyEvent event) {
        String key = String.valueOf(event.getDisplayLabel());
        if ( key.matches("[a-zA-Z0-9]+" )) ((EditText) view).setText(key);
        else ((EditText) view).getText().clear();
        return true;
    }

    public void onKey(String key) {
        if (keyInFocus != null)
            ((EditText) keyInFocus).setText(key);
    }

    private boolean focusKey(View v, int keyCode, KeyEvent event) {
        keyInFocus = v;
        return true;
    }

    public interface OnCardItemSelectedListener {
        /**
         * Called when a CardView has been clicked.
         *
         * @param menuItemId the relevant id of the menu item for the card.
         */
        void onActionSelected(@IdRes int menuItemId);
    }
}
