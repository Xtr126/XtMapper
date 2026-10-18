package xtr.keymapper.macro

import xtr.keymapper.keymap.KeymapProfile

object MacroIdUtils {
    const val TAG = "MACRO"

    fun getLines(linesToWrite: ArrayList<String?>, profile: KeymapProfile) {
        profile.macroIdMap.forEach { (macroId, macro) ->
            linesToWrite.add("$TAG $macroId ${macro.triggerKey}")
        }
    }
}
