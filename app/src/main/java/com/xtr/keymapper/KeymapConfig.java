package com.xtr.keymapper;
import android.content.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class KeymapConfig {
    private final Context context;
    public static final String config_name = "/keymap_config";
<<<<<<< HEAD
    private final String[] keys = new String[36];
    private final Float[] x = new Float[36];
    private final Float[] y = new Float[36];

    public String[] dpad1Config;
    public String[] dpad2Config;
=======
    private final String[] keys = new String[38]; // element 0 to 35 for A-Z 0-9
    private final Float[] keyX = new Float[38]; // element 36 and 37 for dpad1 dpad2
    private final Float[] keyY = new Float[38];
    private final String[] dpad1 = new String[3];
    private final String[] dpad2 = new String[3];
>>>>>>> dev

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
        return keyX;
    }

    public Float[] getY() {
        return keyY;
    }

    public void loadConfig() throws IOException {
        List<String> stream = Files.readAllLines(Paths.get(getConfigPath(context)));
        stream.forEach(s -> {
            String[] xy = s.split("\\s+"); // Split a String like KEY_G 760.86346 426.18607
            switch (xy[0]){
                case "UDLR_DPAD": {
<<<<<<< HEAD
                    dpad1Config = xy;
                    break;
                }
                case "WASD_DPAD": {
                    dpad2Config = xy;
=======
                    keys[36] = xy[0];
                    keyX[36] = Float.parseFloat(xy[1]);
                    keyY[36] = Float.parseFloat(xy[2]);
                    dpad1[0] = xy[3]; // diameter
                    dpad1[1] = xy[4]; // absolute x position of pivot (center)
                    dpad1[2] = xy[5]; // absolute y position of pivot (center)
                    break;
                }
                case "WASD_DPAD": {
                    keys[37] = xy[0];
                    keyX[37] = Float.parseFloat(xy[1]);
                    keyY[37] = Float.parseFloat(xy[2]);
                    dpad2[0] = xy[3];
                    dpad2[1] = xy[4];
                    dpad2[2] = xy[5];
>>>>>>> dev
                    break;
                }
                default: {
                    int i = Utils.obtainIndex(xy[0]);
                    keys[i] = xy[0].substring(4);
<<<<<<< HEAD
                    x[i] = Float.valueOf(xy[1]);
                    y[i] = Float.valueOf(xy[2]);
=======
                    keyX[i] = Float.valueOf(xy[1]);
                    keyY[i] = Float.valueOf(xy[2]);
>>>>>>> dev
                    break;
                }
            }
        });

    }
}