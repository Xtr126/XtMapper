package xtr.keymapper.macro;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.Set;


public class MacroSharedPreferences {
    private final SharedPreferences sharedPref;
    private final SharedPreferences.Editor editor;

    public MacroSharedPreferences(@NonNull Context context) {
        sharedPref = context.getSharedPreferences("macros", Context.MODE_PRIVATE);
        editor = sharedPref.edit();
    }

    public void registerOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        sharedPref.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(SharedPreferences. OnSharedPreferenceChangeListener listener) {
        sharedPref.unregisterOnSharedPreferenceChangeListener(listener);
    }

    /**
     * Renames a key of macro in key-value pair of SharedPreferences
     * @param oldId Id of macro to be renamed
     * @param newId New id
     */
    public void renameMacro(String oldId, String newId) {
        if (!oldId.equals(newId)) {
            editor.putString(newId, sharedPref.getString(oldId, null));
            editor.remove(oldId);
            editor.apply();
        }
    }

    /**
     * Retrieves the value associated with a key
     * @param id Macro identifier string
     * @return Content of macro
     */
    public Macro getMacro(String id) {
        String data = sharedPref.getString(id, null);
        if (data != null) return new Macro(data);
        else return null;
    }

    /**
     * Retrieves all keys in SharedPreferences
     */
    public Set<String> getMacroIds() {
        return sharedPref.getAll().keySet();
    }

    /**
     * Retrieves all key-value pairs in SharedPreferences
     */
    public Map<String, ?> getAllMacros() {
        return sharedPref.getAll();
    }

    /**
     * Removes a key-value pair from SharedPreferences.
     * @param id Macro identifier string
     */
    public void removeMacro(String id) {
        editor.remove(id);
        editor.apply();
    }

    /**
     * Clears all key-value pairs in SharedPreferences
     */
    public void clearAllMacros() {
        editor.clear();
        editor.apply();
    }

    /**
     * Adds a new macro with the next available identifier string like "macro0", "macro1", etc.
     * @param value Content of macro
     */
    public void addMacroWithNextAvailableId(String value) {
        int index = 0;
        String newKey;

        // Find the next available macro key
        do {
            newKey = "macro" + index;
            index++;
        } while (sharedPref.contains(newKey));

        // Store the new key-value pair
        editor.putString(newKey, value);
        editor.apply();
    }
}