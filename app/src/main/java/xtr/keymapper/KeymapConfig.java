package xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import xtr.keymapper.profiles.ProfilesApps;

public class KeymapConfig {
    private final SharedPreferences sharedPref;
    public String profile, device;
    public Float mouseSensitivity, scrollSpeed;
    public boolean ctrlMouseWheelZoom, ctrlDragMouseGesture;

    public int stopServiceShortcutKey, launchEditorShortcutKey, switchProfileShortcutKey;
    public String stopServiceShortcutKeyModifier, launchEditorShortcutKeyModifier, switchProfileShortcutKeyModifier;

    public static final String KEY_CTRL = "Ctrl", KEY_ALT = "Alt";

    public KeymapConfig(Context context) {
        sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE);
        loadSharedPrefs();
    }

    private void loadSharedPrefs() {
        profile = sharedPref.getString("profile", ProfilesApps.defaultProfile);
        device = sharedPref.getString("device", "null");
        mouseSensitivity = sharedPref.getFloat("mouse_sensitivity_multiplier", 1);
        scrollSpeed = sharedPref.getFloat("scroll_speed_multiplier", 1);
        ctrlMouseWheelZoom = sharedPref.getBoolean("ctrl_mouse_wheel_zoom", false);
        ctrlDragMouseGesture = sharedPref.getBoolean("ctrl_drag_mouse_gesture", true);

        launchEditorShortcutKey = sharedPref.getInt("launch_editor_shortcut", -1);
        stopServiceShortcutKey = sharedPref.getInt("stop_service_shortcut", -1);
        switchProfileShortcutKey = sharedPref.getInt("switch_profile_shortcut", -1);

        launchEditorShortcutKeyModifier = sharedPref.getString("launch_editor_shortcut_modifier", KEY_CTRL);
        stopServiceShortcutKeyModifier = sharedPref.getString("stop_service_shortcut_modifier", KEY_CTRL);
        switchProfileShortcutKeyModifier = sharedPref.getString("switch_profile_shortcut_modifier", KEY_CTRL);
    }

    public void applySharedPrefs() {
        sharedPref.edit().putString("profile", profile)
            .putString("device", device)
            .putFloat("mouse_sensitivity_multiplier", mouseSensitivity)
            .putFloat("scroll_speed_multiplier", scrollSpeed)
            .putBoolean("ctrl_mouse_wheel_zoom", ctrlMouseWheelZoom)
            .putBoolean("ctrl_drag_mouse_gesture", ctrlDragMouseGesture)
            .putInt("stop_service_shortcut", stopServiceShortcutKey)
            .putInt("launch_editor_shortcut", launchEditorShortcutKey)
            .putInt("switch_profile_shortcut", switchProfileShortcutKey)
            .putString("stop_service_shortcut_modifier", stopServiceShortcutKeyModifier)
            .putString("launch_editor_shortcut_modifier", launchEditorShortcutKeyModifier)
            .putString("switch_profile_shortcut_modifier", switchProfileShortcutKeyModifier)
            .apply();
    }
}
