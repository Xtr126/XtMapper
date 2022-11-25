package xtr.keymapper.dpad;

import android.content.Context;
import android.content.SharedPreferences;

public class DpadConfig {
    private final Context context;

    public DpadConfig(Context context){
        this.context = context;
    }

    public Float getDpadRadiusMultiplier(){
        SharedPreferences sharedPref =
                context.getSharedPreferences("settings", Context.MODE_PRIVATE);

        return sharedPref.getFloat("dpad_radius", 1f);
    }

    public void setDpadRadiusMultiplier(Float radius){
        SharedPreferences sharedPref =
                context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putFloat("dpad_radius", radius);
        editor.apply();
    }
}
