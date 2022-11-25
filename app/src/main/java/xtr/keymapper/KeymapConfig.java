package xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import xtr.keymapper.aim.MouseAimKey;
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
    public MouseAimKey mouseAimKey = null;
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
                    mouseAimKey = new MouseAimKey(data);
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
