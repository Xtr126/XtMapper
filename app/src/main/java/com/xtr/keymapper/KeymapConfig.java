package com.xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import com.xtr.keymapper.fragment.Profiles;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class KeymapConfig {
    private final Context context;
    public static final String config_name = "/config.";
    private final String[] keys = new String[38]; // element 0 to 35 for A-Z 0-9
    private final Float[] keyX = new Float[38]; // element 36 and 37 for dpad1 dpad2
    private final Float[] keyY = new Float[38];
    public String[] dpad1 = null;
    public String[] dpad2 = null;
    private final SharedPreferences sharedPref;
    private final SharedPreferences.Editor sharedPrefEditor;

    public KeymapConfig(Context context) {
        this.context = context;
        sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE);
        sharedPrefEditor = sharedPref.edit();
    }

    public static String getSettings(Context context){
        KeymapConfig keymapConfig = new KeymapConfig(context);
        String device = keymapConfig.getDevice();
        float sensitivity = keymapConfig.getMouseSensitivity();
        return device + " " + sensitivity;
    }

    public String getProfile(){
        return sharedPref.getString("profile", Profiles.defaultProfile);
    }

    public String getDevice(){
        return sharedPref.getString("device", "null");
    }

    public Float getMouseSensitivity(){
        return sharedPref.getFloat("mouse_sensitivity_multiplier", 1);
    }

    public void setProfile(String newProfile){
        sharedPrefEditor.putString("profile", newProfile);
        sharedPrefEditor.apply();
    }

    public void setDevice(String newDevice){
        sharedPrefEditor.putString("device", newDevice);
        sharedPrefEditor.apply();
    }

    public void setMouseSensitivity(Float sensitivity){
        sharedPrefEditor.putFloat("mouse_sensitivity_multiplier", sensitivity);
        sharedPrefEditor.apply();
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
        return context.getFilesDir() + config_name + getProfile();
    }

    public void writeConfig(StringBuilder linesToWrite) throws IOException {
        FileWriter fileWriter = new FileWriter(getConfigPath());
        fileWriter.append(linesToWrite);
        fileWriter.close();
    }

    public void loadConfig() throws IOException {
        List<String> stream = Files.readAllLines(Paths.get(getConfigPath()));
        stream.forEach(s -> {
            String[] xy = s.split("\\s+"); // Split a String like KEY_G 760.86346 426.18607
            switch (xy[0]){
                case "UDLR_DPAD": {
                    keys[36] = xy[0];
                    keyX[36] = Float.parseFloat(xy[1]);
                    keyY[36] = Float.parseFloat(xy[2]);
                    dpad1 = new String[3];
                    dpad1[0] = xy[3]; // diameter
                    dpad1[1] = xy[4]; // absolute x position of pivot (center)
                    dpad1[2] = xy[5]; // absolute y position of pivot (center)
                    break;
                }
                case "WASD_DPAD": {
                    keys[37] = xy[0];
                    keyX[37] = Float.parseFloat(xy[1]);
                    keyY[37] = Float.parseFloat(xy[2]);
                    dpad2 = new String[3];
                    dpad2[0] = xy[3];
                    dpad2[1] = xy[4];
                    dpad2[2] = xy[5];
                    break;
                }
                default: {
                    int i = Utils.obtainIndex(xy[0]);
                    keys[i] = xy[0].substring(4);
                    keyX[i] = Float.valueOf(xy[1]);
                    keyY[i] = Float.valueOf(xy[2]);
                    break;
                }
            }
        });
    }
}
