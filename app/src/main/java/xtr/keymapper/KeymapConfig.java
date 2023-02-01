package xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import xtr.keymapper.mouse.MouseAimConfig;
import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.fragment.Profiles;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class KeymapConfig {
    private final Context context;
    public static final String config_name = "/config.";
    private final String[] keys = new String[36]; // element 0 to 35 for A-Z 0-9
    private final Float[] keyX = new Float[36];
    private final Float[] keyY = new Float[36];
    public Dpad dpad1 = null;
    public Dpad dpad2 = null;
    public MouseAimConfig mouseAimConfig = null;
    private final SharedPreferences sharedPref;
    private final SharedPreferences.Editor sharedPrefEditor;

    public String profile, device;
    public Float mouseSensitivity, scrollSpeed;
    public int stopServiceShortcutKey, launchEditorShortcutKey;
    public boolean ctrlMouseWheelZoom, ctrlDragMouseGesture;

    public KeymapConfig(Context context) {
        this.context = context;
        sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE);
        sharedPrefEditor = sharedPref.edit();
    }

    public KeymapConfig loadSharedPrefs() {
        profile = sharedPref.getString("profile", Profiles.defaultProfile);
        device = sharedPref.getString("device", "null");
        mouseSensitivity = sharedPref.getFloat("mouse_sensitivity_multiplier", 1);
        scrollSpeed = sharedPref.getFloat("scroll_speed_multiplier", 1);
        stopServiceShortcutKey = sharedPref.getInt("stop_service_shortcut", -1);
        launchEditorShortcutKey = sharedPref.getInt("launch_editor_shortcut", -1);
        ctrlMouseWheelZoom = sharedPref.getBoolean("ctrl_mouse_wheel_zoom", false);
        ctrlDragMouseGesture = sharedPref.getBoolean("ctrl_drag_mouse_gesture", true);
        return this;
    }

    public void applySharedPrefs() {
        sharedPrefEditor.putString("profile", profile)
            .putString("device", device)
            .putFloat("mouse_sensitivity_multiplier", mouseSensitivity)
            .putFloat("scroll_speed_multiplier", scrollSpeed)
            .putInt("stop_service_shortcut", stopServiceShortcutKey)
            .putInt("launch_editor_shortcut", launchEditorShortcutKey)
            .putBoolean("ctrl_mouse_wheel_zoom", ctrlMouseWheelZoom)
            .putBoolean("ctrl_drag_mouse_gesture", ctrlDragMouseGesture)
            .apply();
    }

    public String[] getKeys() {
        return keys;
    }

    public Float[] getX() {
        return keyX;
    }

    public Float[] getY() {
        return keyY;
    }

    public String getConfigPath(){
        return context.getFilesDir() + config_name + profile;
    }

    public void writeConfig(StringBuilder linesToWrite) throws IOException {
        FileWriter fileWriter = new FileWriter(getConfigPath());
        fileWriter.append(linesToWrite);
        fileWriter.close();
    }

    public void loadConfig() throws IOException {
        List<String> stream = Files.readAllLines(Paths.get(getConfigPath()));
        stream.forEach(s -> {
            String[] data = s.split("\\s+"); // Split a String like KEY_G 760.86346 426.18607
            switch (data[0]){
                case "UDLR_DPAD": {
                    dpad1 = new Dpad(data);
                    break;
                }
                case "WASD_DPAD": {
                    dpad2 = new Dpad(data);
                    break;
                }
                case "MOUSE_AIM": {
                    mouseAimConfig = new MouseAimConfig().parse(data);
                    break;
                }
                default: {
                    int i = Utils.obtainIndex(data[0]);
                    if ( i > -1 ) {
                        keys[i] = data[0].substring(4);
                        keyX[i] = Float.valueOf(data[1]);
                        keyY[i] = Float.valueOf(data[2]);
                    }
                    break;
                }
            }
        });
    }
}
