package xtr.keymapper.keymap;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.macro.Macro;
import xtr.keymapper.macro.MacroIdUtils;
import xtr.keymapper.mouse.MouseAimConfig;
import xtr.keymapper.swipekey.SwipeKey;

public class KeymapProfiles {
    public final SharedPreferences sharedPref;
    public static final String MOUSE_RIGHT = "MOUSE_RIGHT";
    private final Context context;

    public KeymapProfiles(Context context) {
        this.context = context;
        sharedPref = context.getSharedPreferences("profiles", MODE_PRIVATE);
    }

    public Map<String, KeymapProfile> getAllProfiles(){
        Map<String, KeymapProfile> allProfiles = new HashMap<>();
        sharedPref.getAll().forEach((BiConsumer<String, Object>) (key, lines) ->
                allProfiles.put(key, getProfile((Set<String>) lines)));
        return allProfiles;
    }

    public Map<String, KeymapProfile> getAllProfilesForApp(String packageName){
        Map<String, KeymapProfile> appProfiles = new HashMap<>();
        sharedPref.getAll().forEach((BiConsumer<String, Object>) (key, lines) -> {
            KeymapProfile profile = getProfile((Set<String>) lines);
            if(profile.packageName.equals(packageName))
                appProfiles.put(key, profile);
        });
        return appProfiles;
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
        saveProfile(profileName, new ArrayList<>(stringSet), packageName, stringSet.contains("ENABLED"), 0, 0);
    }

    public boolean isProfileEnabled(String profileName) {
        Set<String> stringSet = sharedPref.getStringSet(profileName, null);
        return stringSet.contains("ENABLED");
    }

    public boolean profileExistsWithPackageName(String packageName){
        return !getAllProfilesForApp(packageName).isEmpty();
    }

    public void setProfileEnabled(String profileName, boolean enabled) {
        Set<String> stringSet = sharedPref.getStringSet(profileName, null);
        String packageName = null;
        for (String line : stringSet) {
            String[] data = line.split("\\s+");
            if (data[0].equals("APPLICATION"))
                packageName = data[1];
        }
        if (packageName == null) return;

        for (var entry : sharedPref.getAll().entrySet()) {
            // disable or enable all profiles for app for consistency
            KeymapProfile profile = getProfile((Set<String>) entry.getValue());
            if(profile.packageName.equals(packageName)) {
                Set<String> lines = sharedPref.getStringSet(entry.getKey(), null);
                saveProfile(entry.getKey(), new ArrayList<>(lines), packageName, enabled, profile.xRes, profile.yRes);
            }
        }
    }

    public void saveProfile(String profileName, ArrayList<String> lines, String packageName, boolean enabled, int xRes, int yRes) {
        lines.removeIf(line -> line.contains("APPLICATION"));
        lines.add("APPLICATION " + packageName);

        if (xRes > 0 && yRes > 0) {
            lines.removeIf(line -> line.contains("SCREENSIZE"));
            lines.add("SCREENSIZE " + xRes + " " + yRes);
        }

        lines.removeIf(line -> line.contains("ENABLED"));
        if (enabled) lines.add("ENABLED");
        Set<String> stringSet = new HashSet<>(lines);
        sharedPref.edit()
                .putStringSet(profileName, stringSet)
                .apply();
    }

    public void deleteProfile(String profileName){
        sharedPref.edit().remove(profileName).apply();
    }

    public KeymapProfile getProfile(String profileName, boolean loadMacros) {
        Set<String> stringSet = sharedPref.getStringSet(profileName, null);
        KeymapProfile keymapProfile = getProfile(stringSet);
        if (loadMacros) keymapProfile.loadMacros(context);
        return keymapProfile;
    }

    public KeymapProfile getProfile(Set<String> lines) {
        KeymapProfile profile = new KeymapProfile();
        profile.disabled = true;
        if (lines != null) lines.forEach(line -> {

            String[] data = line.split("\\s+"); // Split a String like KEY_G 760.86346 426.18607
            switch (data[0]){
                case Dpad.TAG:
                    if (data.length >= 12)
                        for (int i = 0; i < profile.dpadArray.length; i++)
                            if (profile.dpadArray[i] == null) {
                                profile.dpadArray[i] = new Dpad(data);
                                break;
                            }
                    break;

                case Dpad.TAG_ARROW_KEYS:
                    if (data.length >= 12)
                        profile.dpadUdlr = new Dpad(data);
                    break;

                case MouseAimConfig.TAG:
                    profile.mouseAimConfig = new MouseAimConfig().parse(data);
                    break;

                case MOUSE_RIGHT:
                    profile.rightClick = new KeymapProfileKey();
                    profile.rightClick.x = Float.parseFloat(data[1]);
                    profile.rightClick.y = Float.parseFloat(data[2]);
                    break;

                case "APPLICATION":
                    profile.packageName = data[1];
                    break;

                case "ENABLED":
                    profile.disabled = false;
                    break;

                case "SCREENSIZE":
                    profile.xRes = Integer.parseInt(data[1]);
                    profile.yRes = Integer.parseInt(data[2]);
                    break;

                case MacroIdUtils.TAG:
                    if (data.length >= 3) {
                        String macroId = data[1];
                        Macro macro = new Macro();
                        macro.triggerKey = data[2];
                        profile.macroIdMap.put(macroId, macro);
                    }
                    break;

                case SwipeKey.TAG:
                    if (data.length > 6) profile.swipeKeys.add(new SwipeKey(data));
                    break;

                default: {
                    if (data.length > 3) {
                        final KeymapProfileKey key = new KeymapProfileKey();
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
