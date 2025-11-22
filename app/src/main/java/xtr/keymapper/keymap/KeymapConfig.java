package xtr.keymapper.keymap;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.Parcelable;

public class KeymapConfig implements Parcelable {
    public boolean showControls;
    public float showControlsOpacity;
    public float floatingKeysSize;

    private SharedPreferences sharedPref;
    public Float mouseSensitivity = 1f, scrollSpeed = 1f;
    public boolean ctrlMouseWheelZoom, ctrlDragMouseGesture, rightClickMouseAim, keyGraveMouseAim;
    public boolean disableAutoProfiling, useShizuku, editorOverlay;

    public String pauseResumeShortcutKey = "KEY_P"; // KEY_P
    public String launchEditorShortcutKey = "KEY_E"; // KEY_E
    public String switchProfileShortcutKey = "KEY_S"; // KEY_S
    public int swipeDelayMs;
    public String pauseResumeShortcutKeyModifier, launchEditorShortcutKeyModifier, switchProfileShortcutKeyModifier;
    public int pointerMode;

    public static final String KEY_CTRL = "Ctrl", KEY_ALT = "Alt";
    public static final int TOUCHPAD_DIRECT = 3;
    public static final int TOUCHPAD_RELATIVE = 4;
    public static final int TOUCHPAD_DISABLED = 5;

    public static final int POINTER_SYSTEM = 6;
    public static final int POINTER_OVERLAY = 7;
    public static final int POINTER_COMBINED = 8;

    public String mouseAimShortcutKey;
    public boolean mouseAimToggle;
    public int touchpadInputMode = TOUCHPAD_DISABLED;

    public KeymapConfig(Context context) {
        if (context != null) {
            sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE);
            try {
                loadSharedPrefs();
            } catch (ClassCastException e) {
                sharedPref.edit().clear().apply();
                loadSharedPrefs();
            }
        }
    }

    protected KeymapConfig(Parcel in) {
        showControls = in.readByte() != 0;
        showControlsOpacity = in.readFloat();
        if (in.readByte() == 0) {
            mouseSensitivity = null;
        } else {
            mouseSensitivity = in.readFloat();
        }
        if (in.readByte() == 0) {
            scrollSpeed = null;
        } else {
            scrollSpeed = in.readFloat();
        }
        ctrlMouseWheelZoom = in.readByte() != 0;
        ctrlDragMouseGesture = in.readByte() != 0;
        rightClickMouseAim = in.readByte() != 0;
        keyGraveMouseAim = in.readByte() != 0;
        disableAutoProfiling = in.readByte() != 0;
        useShizuku = in.readByte() != 0;
        editorOverlay = in.readByte() != 0;
        pauseResumeShortcutKey = in.readString();
        launchEditorShortcutKey = in.readString();
        switchProfileShortcutKey = in.readString();
        swipeDelayMs = in.readInt();
        pauseResumeShortcutKeyModifier = in.readString();
        launchEditorShortcutKeyModifier = in.readString();
        switchProfileShortcutKeyModifier = in.readString();
        pointerMode = in.readInt();
        mouseAimShortcutKey = in.readString();
        mouseAimToggle = in.readByte() != 0;
        touchpadInputMode = in.readInt();
        floatingKeysSize = in.readFloat();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (showControls ? 1 : 0));
        dest.writeFloat(showControlsOpacity);
        if (mouseSensitivity == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeFloat(mouseSensitivity);
        }
        if (scrollSpeed == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeFloat(scrollSpeed);
        }
        dest.writeByte((byte) (ctrlMouseWheelZoom ? 1 : 0));
        dest.writeByte((byte) (ctrlDragMouseGesture ? 1 : 0));
        dest.writeByte((byte) (rightClickMouseAim ? 1 : 0));
        dest.writeByte((byte) (keyGraveMouseAim ? 1 : 0));
        dest.writeByte((byte) (disableAutoProfiling ? 1 : 0));
        dest.writeByte((byte) (useShizuku ? 1 : 0));
        dest.writeByte((byte) (editorOverlay ? 1 : 0));
        dest.writeString(pauseResumeShortcutKey);
        dest.writeString(launchEditorShortcutKey);
        dest.writeString(switchProfileShortcutKey);
        dest.writeInt(swipeDelayMs);
        dest.writeString(pauseResumeShortcutKeyModifier);
        dest.writeString(launchEditorShortcutKeyModifier);
        dest.writeString(switchProfileShortcutKeyModifier);
        dest.writeInt(pointerMode);
        dest.writeString(mouseAimShortcutKey);
        dest.writeByte((byte) (mouseAimToggle ? 1 : 0));
        dest.writeInt(touchpadInputMode);
        dest.writeFloat(floatingKeysSize);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<KeymapConfig> CREATOR = new Creator<>() {
        @Override
        public KeymapConfig createFromParcel(Parcel in) {
            return new KeymapConfig(in);
        }

        @Override
        public KeymapConfig[] newArray(int size) {
            return new KeymapConfig[size];
        }
    };

    private void loadSharedPrefs() throws ClassCastException {
        mouseSensitivity = sharedPref.getFloat("mouse_sensitivity_multiplier", 1);
        scrollSpeed = sharedPref.getFloat("scroll_speed_multiplier", 1);
        ctrlMouseWheelZoom = sharedPref.getBoolean("ctrl_mouse_wheel_zoom", false);
        ctrlDragMouseGesture = sharedPref.getBoolean("ctrl_drag_mouse_gesture", true);
        mouseAimToggle = sharedPref.getBoolean("mouse_aim_shortcut_toggle", true);
        disableAutoProfiling = sharedPref.getBoolean("disable_auto_profile", true);
        useShizuku = sharedPref.getBoolean("use_shizuku", false);
        editorOverlay = sharedPref.getBoolean("editor_overlay", false);

        showControls = sharedPref.getBoolean("show_controls", false);
        showControlsOpacity = sharedPref.getFloat("show_controls_opacity", 0.2f);

        launchEditorShortcutKey = sharedPref.getString("launch_editor_shortcut", launchEditorShortcutKey);
        pauseResumeShortcutKey = sharedPref.getString("pause_resume_shortcut", pauseResumeShortcutKey);
        switchProfileShortcutKey = sharedPref.getString("switch_profile_shortcut", switchProfileShortcutKey);
        mouseAimShortcutKey = sharedPref.getString("mouse_aim_shortcut", "");

        launchEditorShortcutKeyModifier = sharedPref.getString("launch_editor_shortcut_modifier", KEY_CTRL);
        pauseResumeShortcutKeyModifier = sharedPref.getString("pause_resume_shortcut_modifier", KEY_CTRL);
        switchProfileShortcutKeyModifier = sharedPref.getString("switch_profile_shortcut_modifier", KEY_CTRL);

        keyGraveMouseAim = sharedPref.getBoolean("key_grave_mouse_aim", true);
        rightClickMouseAim = sharedPref.getBoolean("right_click_mouse_aim", true);

        swipeDelayMs = sharedPref.getInt("swipe_delay_ms", 10);

        touchpadInputMode = sharedPref.getInt("touchpad_input_mode", TOUCHPAD_DISABLED);
        pointerMode = sharedPref.getInt("pointer_mode", POINTER_OVERLAY);

        floatingKeysSize = sharedPref.getFloat("floating_keys_size", 1);
    }

    public void applySharedPrefs() {
        sharedPref.edit().putFloat("mouse_sensitivity_multiplier", mouseSensitivity)
                .putFloat("scroll_speed_multiplier", scrollSpeed)
                .putBoolean("ctrl_mouse_wheel_zoom", ctrlMouseWheelZoom)
                .putBoolean("ctrl_drag_mouse_gesture", ctrlDragMouseGesture)
                .putBoolean("key_grave_mouse_aim", keyGraveMouseAim)
                .putBoolean("right_click_mouse_aim", rightClickMouseAim)
                .putBoolean("mouse_aim_shortcut_toggle", mouseAimToggle)
                .putBoolean("disable_auto_profile", disableAutoProfiling)
                .putBoolean("use_shizuku", useShizuku)
                .putBoolean("editor_overlay", editorOverlay)
                .putBoolean("show_controls", showControls)
                .putFloat("show_controls_opacity", showControlsOpacity)
                .putString("pause_resume_shortcut", pauseResumeShortcutKey)
                .putString("launch_editor_shortcut", launchEditorShortcutKey)
                .putString("switch_profile_shortcut", switchProfileShortcutKey)
                .putString("mouse_aim_shortcut", mouseAimShortcutKey)
                .putString("pause_resume_shortcut_modifier", pauseResumeShortcutKeyModifier)
                .putString("launch_editor_shortcut_modifier", launchEditorShortcutKeyModifier)
                .putString("switch_profile_shortcut_modifier", switchProfileShortcutKeyModifier)
                .putInt("touchpad_input_mode", touchpadInputMode)
                .putInt("swipe_delay_ms", swipeDelayMs)
                .putInt("pointer_mode", pointerMode)
                .putFloat("floating_keys_size", floatingKeysSize)
                .apply();
    }

}
