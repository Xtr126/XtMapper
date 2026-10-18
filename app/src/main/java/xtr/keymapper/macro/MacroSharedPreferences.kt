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

    /**
     * Renames a key of macro in key-value pair of SharedPreferences
     * @param oldId Id of macro to be renamed
     * @param newId New id
     */
    fun renameMacro(oldId: String, newId: String) {
        if (oldId != newId) {
            editor.putString(newId, sharedPref.getString(oldId, null))
            editor.remove(oldId)
            editor.apply()
        }
    }

    /**
     * Retrieves the value associated with a key
     * @param id Macro identifier string
     * @return Content of macro
     */
    fun getMacro(id: String): Macro {
        val data = sharedPref.getString(id, null)
        return if (data != null) Macro(data) else Macro()
    }

    /**
     * Retrieves all keys in SharedPreferences
     */
    fun getMacroIds(): Set<String> {
        return sharedPref.all.keys
    }

    /**
     * Removes a key-value pair from SharedPreferences.
     * @param id Macro identifier string
     */
    fun removeMacro(id: String) {
        editor.remove(id)
        editor.apply()
    }

    /**
     * Adds a new macro with the next available identifier string like "macro0", "macro1", etc.
     * @param value Content of macro
     */
    fun addMacroWithNextAvailableId(value: String) {
        var index = 0
        var newKey: String

        // Find the next available macro key
        do {
            newKey = "macro$index"
            index++
        } while (sharedPref.contains(newKey))

        // Store the new key-value pair
        editor.putString(newKey, value)
        editor.apply()
    }
}
