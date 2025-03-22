package xtr.keymapper.macro;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import xtr.keymapper.keymap.KeymapProfile;

public class MacroIdUtils {
    public static final String TAG = "MACRO_IDS";

    private static String encode(Set<String> macroIds) {
        return String.join(";", macroIds);
    }

    public static String getLine(KeymapProfile profile) {
        return TAG + " " + encode(profile.getMacroIds());
    }

    public static List<String> decode(String data) {
        return Arrays.asList(data.split(";"));
    }
}
