package xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import xtr.keymapper.profiles.ProfilesApps;

public class KeymapConfig {
    private final SharedPreferences sharedPref;
    public String profile, device;
    public Float mouseSensitivity, scrollSpeed;
    public int stopServiceShortcutKey, launchEditorShortcutKey;
    public boolean ctrlMouseWheelZoom, ctrlDragMouseGesture;

    public KeymapConfig(Context context) {
        sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE);
        loadSharedPrefs();
    }

    private void loadSharedPrefs() {
        profile = sharedPref.getString("profile", ProfilesApps.defaultProfile);
        device = sharedPref.getString("device", "null");
        mouseSensitivity = sharedPref.getFloat("mouse_sensitivity_multiplier", 1);
        scrollSpeed = sharedPref.getFloat("scroll_speed_multiplier", 1);
        stopServiceShortcutKey = sharedPref.getInt("stop_service_shortcut", -1);
        launchEditorShortcutKey = sharedPref.getInt("launch_editor_shortcut", -1);
        ctrlMouseWheelZoom = sharedPref.getBoolean("ctrl_mouse_wheel_zoom", false);
        ctrlDragMouseGesture = sharedPref.getBoolean("ctrl_drag_mouse_gesture", true);
    }

    public void applySharedPrefs() {
        sharedPref.edit().putString("profile", profile)
            .putString("device", device)
            .putFloat("mouse_sensitivity_multiplier", mouseSensitivity)
            .putFloat("scroll_speed_multiplier", scrollSpeed)
            .putInt("stop_service_shortcut", stopServiceShortcutKey)
            .putInt("launch_editor_shortcut", launchEditorShortcutKey)
            .putBoolean("ctrl_mouse_wheel_zoom", ctrlMouseWheelZoom)
            .putBoolean("ctrl_drag_mouse_gesture", ctrlDragMouseGesture)
            .apply();
    }
}
