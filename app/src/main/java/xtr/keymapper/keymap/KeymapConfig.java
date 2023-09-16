package xtr.keymapper.keymap;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class KeymapConfig implements Parcelable {
    private SharedPreferences sharedPref;
    public Float mouseSensitivity, scrollSpeed;
    public Float dpadRadiusMultiplier;
    public boolean ctrlMouseWheelZoom, ctrlDragMouseGesture, rightClickMouseAim, keyGraveMouseAim;

    public int pauseResumeShortcutKey, launchEditorShortcutKey, switchProfileShortcutKey;
    public int swipeDelayMs;
    public String pauseResumeShortcutKeyModifier, launchEditorShortcutKeyModifier, switchProfileShortcutKeyModifier;

    public static final String KEY_CTRL = "Ctrl", KEY_ALT = "Alt";
    public static final String TOGGLE = "Toggle", HOLD = "Hold";
    public int mouseAimShortcutKey;
    public boolean mouseAimToggle;

    public KeymapConfig(Context context) {
        sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE);
        loadSharedPrefs();
    }

    protected KeymapConfig(Parcel in) {
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
        if (in.readByte() == 0) {
            dpadRadiusMultiplier = null;
        } else {
            dpadRadiusMultiplier = in.readFloat();
        }
        ctrlMouseWheelZoom = in.readByte() != 0;
        ctrlDragMouseGesture = in.readByte() != 0;
        rightClickMouseAim = in.readByte() != 0;
        keyGraveMouseAim = in.readByte() != 0;
        pauseResumeShortcutKey = in.readInt();
        launchEditorShortcutKey = in.readInt();
        switchProfileShortcutKey = in.readInt();
        swipeDelayMs = in.readInt();
        pauseResumeShortcutKeyModifier = in.readString();
        launchEditorShortcutKeyModifier = in.readString();
        switchProfileShortcutKeyModifier = in.readString();
        mouseAimShortcutKey = in.readInt();
        mouseAimToggle = in.readByte() != 0;
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

    private void loadSharedPrefs() {
        mouseSensitivity = sharedPref.getFloat("mouse_sensitivity_multiplier", 1);
        scrollSpeed = sharedPref.getFloat("scroll_speed_multiplier", 1);
        ctrlMouseWheelZoom = sharedPref.getBoolean("ctrl_mouse_wheel_zoom", false);
        ctrlDragMouseGesture = sharedPref.getBoolean("ctrl_drag_mouse_gesture", true);
        mouseAimToggle = sharedPref.getBoolean("mouse_aim_shortcut_toggle", true);

        launchEditorShortcutKey = sharedPref.getInt("launch_editor_shortcut", -1);
        pauseResumeShortcutKey = sharedPref.getInt("pause_resume_shortcut", -1);
        switchProfileShortcutKey = sharedPref.getInt("switch_profile_shortcut", -1);
        mouseAimShortcutKey = sharedPref.getInt("mouse_aim_shortcut", -1);

        launchEditorShortcutKeyModifier = sharedPref.getString("launch_editor_shortcut_modifier", KEY_CTRL);
        pauseResumeShortcutKeyModifier = sharedPref.getString("pause_resume_shortcut_modifier", KEY_CTRL);
        switchProfileShortcutKeyModifier = sharedPref.getString("switch_profile_shortcut_modifier", KEY_CTRL);

        keyGraveMouseAim = sharedPref.getBoolean("key_grave_mouse_aim", true);
        rightClickMouseAim = sharedPref.getBoolean("right_click_mouse_aim", false);

        swipeDelayMs = sharedPref.getInt("swipe_delay_ms", 0);
        dpadRadiusMultiplier = sharedPref.getFloat("dpad_radius", 1f);
    }

    public void applySharedPrefs() {
        sharedPref.edit().putFloat("mouse_sensitivity_multiplier", mouseSensitivity)
                .putFloat("scroll_speed_multiplier", scrollSpeed)
                .putFloat("dpad_radius", dpadRadiusMultiplier)
                .putBoolean("ctrl_mouse_wheel_zoom", ctrlMouseWheelZoom)
                .putBoolean("ctrl_drag_mouse_gesture", ctrlDragMouseGesture)
                .putBoolean("key_grave_mouse_aim", keyGraveMouseAim)
                .putBoolean("right_click_mouse_aim", rightClickMouseAim)
                .putBoolean("mouse_aim_shortcut_toggle", mouseAimToggle)
                .putInt("pause_resume_shortcut", pauseResumeShortcutKey)
                .putInt("launch_editor_shortcut", launchEditorShortcutKey)
                .putInt("switch_profile_shortcut", switchProfileShortcutKey)
                .putInt("mouse_aim_shortcut", mouseAimShortcutKey)
                .putString("pause_resume_shortcut_modifier", pauseResumeShortcutKeyModifier)
                .putString("launch_editor_shortcut_modifier", launchEditorShortcutKeyModifier)
                .putString("switch_profile_shortcut_modifier", switchProfileShortcutKeyModifier)
                .putInt("swipe_delay_ms", swipeDelayMs)
                .apply();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
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
        if (dpadRadiusMultiplier == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeFloat(dpadRadiusMultiplier);
        }
        dest.writeByte((byte) (ctrlMouseWheelZoom ? 1 : 0));
        dest.writeByte((byte) (ctrlDragMouseGesture ? 1 : 0));
        dest.writeByte((byte) (rightClickMouseAim ? 1 : 0));
        dest.writeByte((byte) (keyGraveMouseAim ? 1 : 0));
        dest.writeInt(pauseResumeShortcutKey);
        dest.writeInt(launchEditorShortcutKey);
        dest.writeInt(switchProfileShortcutKey);
        dest.writeInt(swipeDelayMs);
        dest.writeString(pauseResumeShortcutKeyModifier);
        dest.writeString(launchEditorShortcutKeyModifier);
        dest.writeString(switchProfileShortcutKeyModifier);
        dest.writeInt(mouseAimShortcutKey);
        dest.writeByte((byte) (mouseAimToggle ? 1 : 0));
    }
}
