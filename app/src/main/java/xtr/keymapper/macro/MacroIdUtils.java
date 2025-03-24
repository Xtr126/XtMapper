package xtr.keymapper.macro;

import java.util.ArrayList;

import xtr.keymapper.keymap.KeymapProfile;

public class MacroIdUtils {
    public static final String TAG = "MACRO";

    public static void getLines(ArrayList<String> linesToWrite, KeymapProfile profile) {
        profile.macroIdMap.forEach((macroId, macro) ->
                linesToWrite.add(TAG + " " + macroId + " " + macro.triggerKey));
    }
}
