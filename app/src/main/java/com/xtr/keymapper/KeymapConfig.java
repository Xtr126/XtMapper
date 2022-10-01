package com.xtr.keymapper;
import android.content.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class KeymapConfig {
    private final Context context;
    public static final String config_name = "/keymap_config";
    private final String[] keys = new String[36];
    private final Float[] x = new Float[36];
    private final Float[] y = new Float[36];

    public String[] dpad1Config = new String[3];
    public String[] dpad2Config = new String[3];

    public KeymapConfig(Context context) {
        this.context = context;
    }

    public static String getConfigPath(Context context){
        return context.getFilesDir() + config_name;
    }

    public String[] getKeys() {
        return keys;
    }

    public Float[] getX() {
        return x;
    }

    public Float[] getY() {
        return y;
    }

    public void loadConfig() throws IOException {
        List<String> stream = Files.readAllLines(Paths.get(getConfigPath(context)));
        stream.forEach(s -> {
            String[] xy = s.split("\\s+"); // Split a String like KEY_G 760.86346 426.18607
            switch (xy[0]){
                case "UDLR_DPAD": {
                    dpad1Config = xy;
                    break;
                }
                case "WASD_DPAD": {
                    dpad2Config = xy;
                    break;
                }
                default: {
                    int i = Utils.obtainIndex(xy[0]);
                    keys[i] = xy[0].substring(4);
                    x[i] = Float.valueOf(xy[1]);
                    y[i] = Float.valueOf(xy[2]);
                    break;
                }
            }
        });

    }
}