package xtr.keymapper.macro

import android.content.Context
import android.content.SharedPreferences

class MacroSharedPreferences(context: Context) {
    private val sharedPref: SharedPreferences = context.getSharedPreferences("macros", Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sharedPref.edit()

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPref.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPref.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun renameMacro(oldId: String, newId: String) {
        if (oldId != newId) {
            editor.putString(newId, sharedPref.getString(oldId, null))
            editor.remove(oldId)
            editor.apply()
        }
    }

    fun getMacro(id: String): Macro {
        val data = sharedPref.getString(id, null)
        return if (data != null) Macro(data) else Macro()
    }

    fun getMacroIds(): Set<String> {
        return sharedPref.all.keys
    }

    fun removeMacro(id: String) {
        editor.remove(id)
        editor.apply()
    }

    fun addMacroWithNextAvailableId(value: String) {
        var index = 0
        var newKey: String

        do {
            newKey = "macro$index"
            index++
        } while (sharedPref.contains(newKey))

        editor.putString(newKey, value)
        editor.apply()
    }
}
