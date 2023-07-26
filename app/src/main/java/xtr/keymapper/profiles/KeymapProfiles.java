package xtr.keymapper.profiles;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.collection.ArraySet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.mouse.MouseAimConfig;
import xtr.keymapper.swipekey.SwipeKey;

public class KeymapProfiles {
    final SharedPreferences sharedPref;
    public static final String MOUSE_RIGHT = "MOUSE_RIGHT";

    public KeymapProfiles(Context context) {
        sharedPref = context.getSharedPreferences("profiles", MODE_PRIVATE);
    }

    public Map<String, Profile> getAllProfiles(){
        Map<String, Profile> profiles = new HashMap<>();
        sharedPref.getAll().forEach((BiConsumer<String, Object>) (key, o) -> profiles.put(key, getProfile(key)));
        return profiles;
    }

    public void renameProfile(String profileName, String newProfile) {
        Set<String> stringSet = sharedPref.getStringSet(profileName, null);
        deleteProfile(profileName);
        sharedPref.edit()
                .putStringSet(newProfile, stringSet)
                .apply();
    }

    public void setProfilePackageName(String profileName, String packageName) {
        Set<String> stringSet = sharedPref.getStringSet(profileName, null);
        saveProfile(profileName, new ArrayList<>(stringSet), packageName);
    }

    public static final class Key {
        public String code;
        public float x;
        public float y;
        public float offset;
    }

     public static final class Profile {
         public String packageName = "xtr.keymapper";
         public Dpad dpadUdlr = null;
         public Dpad dpadWasd = null;
         public MouseAimConfig mouseAimConfig = null;
         public ArrayList<Key> keys = new ArrayList<>();
         public ArrayList<SwipeKey> swipeKeys = new ArrayList<>();
         public Key rightClick;
     }

    public void saveProfile(String profileName, ArrayList<String> lines, String packageName) {
        lines.removeIf(line -> line.contains("APPLICATION"));
        lines.add("APPLICATION " + packageName);
        Set<String> stringSet = new HashSet<>(lines);
        sharedPref.edit()
                .putStringSet(profileName, stringSet)
                .apply();
    }

    public void deleteProfile(String profileName){
        sharedPref.edit().remove(profileName).apply();
    }

    public Profile getProfile(String profileName) {
        Set<String> stream = sharedPref.getStringSet(profileName, null);

        Profile profile = new Profile();
        if (stream != null) stream.forEach(s -> {

            String[] data = s.split("\\s+"); // Split a String like KEY_G 760.86346 426.18607
            switch (data[0]){
                case Dpad.UDLR:
                    if (data.length >= 8) profile.dpadUdlr = new Dpad(data);
                    break;

                case Dpad.WASD:
                    if (data.length >= 8) profile.dpadWasd = new Dpad(data);
                    break;

                case "MOUSE_AIM":
                    profile.mouseAimConfig = new MouseAimConfig().parse(data);
                    break;

                case MOUSE_RIGHT:
                    profile.rightClick = new Key();
                    profile.rightClick.x = Float.parseFloat(data[1]);
                    profile.rightClick.y = Float.parseFloat(data[2]);
                    break;

                case "APPLICATION":
                    profile.packageName = data[1];
                    break;

                case SwipeKey.type:
                    if (data.length > 6) profile.swipeKeys.add(new SwipeKey(data));
                    break;

                default: {
                    if (data.length > 3) {
                        final Key key = new Key();
                        key.code = data[0];
                        key.x = Float.parseFloat(data[1]);
                        key.y = Float.parseFloat(data[2]);
                        key.offset = Float.parseFloat(data[3]);
                        profile.keys.add(key);
                    }
                    break;
                }
            }
        });
        return profile;
    }
}
