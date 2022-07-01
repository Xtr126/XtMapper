package com.xtr.keymapper;
import android.content.Context;

public class KeymapConfig {
    Context context;
    public static final String configPath = "//data/data/com.xtr.keymapper/keymap_config";
    String[] key; Float[] x; Float[] y;

    public KeymapConfig(Context context) {
        this.context = context;
        key = new String[36];
        x = new Float[36];
        y = new Float[36];
    }

    public String[] getKey() {
        return key;
    }

    public Float[] getX() {
        return x;
    }

    public Float[] getY() {
        return y;
    }

    public void loadConfig(String s) {
        String[] xy = s.split("\\s+");
        int i = Utils.obtainIndex(xy[0]);

        key[i] = xy[0].substring(4);
        x[i] = Float.valueOf(xy[1]);
        y[i] = Float.valueOf(xy[2]);
    }

}