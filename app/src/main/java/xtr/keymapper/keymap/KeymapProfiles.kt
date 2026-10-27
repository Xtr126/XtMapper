package xtr.keymapper.keymap

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import xtr.keymapper.keymap.element.Camera
import xtr.keymapper.keymap.element.Dpad
import xtr.keymapper.keymap.element.Key
import xtr.keymapper.keymap.element.MouseAimConfig
import xtr.keymapper.keymap.element.MouseWalk
import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.keymap.element.SwipeKey
import xtr.keymapper.macro.Macro
import xtr.keymapper.macro.MacroIdUtils

class KeymapProfiles @JvmOverloads constructor(
    context: Context,
    @JvmField val sharedPref: SharedPreferences = context.getSharedPreferences("profiles", MODE_PRIVATE)
) {

    private val context: Context = context

    @Suppress("UNCHECKED_CAST")
    fun getAllProfiles(): Map<String, KeymapProfile> {
        val allProfiles = HashMap<String, KeymapProfile>()
        sharedPref.all.forEach { (key, lines) ->
            if (key.startsWith("designated_profile_") || key == KEY_LAST_SELECTED_PROFILE) return@forEach
            if (lines is Set<*>) {
                allProfiles[key] = getProfile(lines as Set<String>)
            }
        }
        return allProfiles
    }

    @Suppress("UNCHECKED_CAST")
    fun getAllProfilesForApp(packageName: String): Map<String, KeymapProfile> {
        val appProfiles = HashMap<String, KeymapProfile>()
        sharedPref.all.forEach { (key, lines) ->
            if (key.startsWith("designated_profile_") || key == KEY_LAST_SELECTED_PROFILE) return@forEach
            if (lines is Set<*>) {
                val profile = getProfile(lines as Set<String>)
                if (profile.packageName == packageName)
                    appProfiles[key] = profile
            }
        }
        return appProfiles
    }

    fun getDesignatedProfileForApp(packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        val profiles = getAllProfilesForApp(packageName)
        if (profiles.isEmpty()) return null

        val designated = sharedPref.getString("designated_profile_$packageName", null)
        if (designated != null && profiles.containsKey(designated)) {
            val profile = profiles[designated]
            if (profile != null && !profile.disabled) {
                return designated
            }
        }
        for ((name, profile) in profiles) {
            if (!profile.disabled) return name
        }
        return if (designated != null && profiles.containsKey(designated)) designated else profiles.keys.firstOrNull()
    }

    fun setDesignatedProfileForApp(packageName: String?, profileName: String?) {
        if (!packageName.isNullOrEmpty() && profileName != null) {
            sharedPref.edit().putString("designated_profile_$packageName", profileName).apply()
        }
    }

    fun renameProfile(profileName: String, newProfile: String) {
        val stringSet = sharedPref.getStringSet(profileName, null)
        deleteProfile(profileName)
        sharedPref.edit()
            .putStringSet(newProfile, stringSet)
            .apply()
        if (sharedPref.getString(KEY_LAST_SELECTED_PROFILE, null) == profileName) {
            setLastSelectedProfile(newProfile)
        }
    }

    fun setProfilePackageName(profileName: String?, packageName: String) {
        val stringSet = sharedPref.getStringSet(profileName, null) ?: return
        saveProfile(profileName, ArrayList<String?>(stringSet), packageName, stringSet.contains("ENABLED"), 0, 0)
    }

    fun isProfileEnabled(profileName: String?): Boolean {
        val stringSet = sharedPref.getStringSet(profileName, null)
        return stringSet?.contains("ENABLED") == true
    }

    fun profileExists(profileName: String?): Boolean {
        if (profileName.isNullOrEmpty()) return false
        return sharedPref.contains(profileName) && sharedPref.getStringSet(profileName, null) != null
    }

    fun profileExistsWithPackageName(packageName: String): Boolean {
        return getAllProfilesForApp(packageName).isNotEmpty()
    }

    fun getLastSelectedProfile(): String {
        val last = sharedPref.getString(KEY_LAST_SELECTED_PROFILE, null)
        if (last != null && profileExists(last)) {
            return last
        }
        return getAllProfiles().keys.firstOrNull() ?: DEFAULT_PROFILE_NAME
    }

    internal fun getStartupProfileName(isShellInit: Boolean): String {
        return if (isShellInit) DEFAULT_PROFILE_NAME else getLastSelectedProfile()
    }

    internal fun recordStartupProfileSelection(profileName: String?, isShellInit: Boolean) {
        if (!isShellInit) setLastSelectedProfile(profileName)
    }

    fun setLastSelectedProfile(profileName: String?) {
        if (!profileName.isNullOrEmpty()) {
            sharedPref.edit().putString(KEY_LAST_SELECTED_PROFILE, profileName).apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun setProfileEnabled(profileName: String, enabled: Boolean) {
        val stringSet = sharedPref.getStringSet(profileName, null) ?: return
        var packageName: String? = null
        for (line in stringSet) {
            val data = line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (data[0] == "APPLICATION" && data.size >= 2)
                packageName = data[1]
        }
        if (packageName == null) return

        for ((key, value) in sharedPref.all) {
            if (key.startsWith("designated_profile_") || key == KEY_LAST_SELECTED_PROFILE) continue
            if (value !is Set<*>) continue
            // disable or enable all profiles for app for consistency
            val profile = getProfile(value as Set<String>)
            if (profile.packageName == packageName) {
                val lines = sharedPref.getStringSet(key, null) ?: continue
                saveProfile(key, ArrayList<String?>(lines), packageName, enabled, profile.xRes, profile.yRes)
            }
        }
    }

    fun saveProfile(
        profileName: String?,
        lines: ArrayList<String?>,
        packageName: String,
        enabled: Boolean,
        xRes: Int,
        yRes: Int
    ) {
        lines.removeIf { it!!.contains("APPLICATION") }
        lines.add("APPLICATION $packageName")

        if (xRes > 0 && yRes > 0) {
            lines.removeIf { it!!.contains("SCREENSIZE") }
            lines.add("SCREENSIZE $xRes $yRes")
        }

        lines.removeIf { it!!.contains("ENABLED") }
        if (enabled) lines.add("ENABLED")
        val stringSet = HashSet(lines)
        sharedPref.edit()
            .putStringSet(profileName, stringSet)
            .apply()

        if (!packageName.isNullOrEmpty()) {
            if (sharedPref.getString("designated_profile_$packageName", null) == null) {
                setDesignatedProfileForApp(packageName, profileName)
            }
        }
    }

    fun deleteProfile(profileName: String?) {
        sharedPref.edit().remove(profileName).apply()
        if (profileName != null && sharedPref.getString(KEY_LAST_SELECTED_PROFILE, null) == profileName) {
            sharedPref.edit().remove(KEY_LAST_SELECTED_PROFILE).apply()
        }
    }

    fun getProfile(profileName: String?, loadMacros: Boolean): KeymapProfile {
        val stringSet = sharedPref.getStringSet(profileName, null)
        val keymapProfile = getProfile(stringSet)
        if (loadMacros) keymapProfile.loadMacros(context)
        return keymapProfile
    }

    fun getProfile(lines: Set<String>?): KeymapProfile = parse(lines)

    companion object {
        const val MOUSE_RIGHT = "MOUSE_RIGHT"
        const val KEY_LAST_SELECTED_PROFILE = "last_selected_profile"
        internal const val DEFAULT_PROFILE_NAME = "Default"

        fun parse(lines: Set<String>?): KeymapProfile {
            val profile = KeymapProfile()
            profile.disabled = true
            lines?.forEach { line ->

                val data = line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray() // Split a String like KEY_G 760.86346 426.18607
                when (data[0]) {
                    MouseAimConfig.TAG ->
                        profile.mouseAimConfig = MouseAimConfig(data)

                    Dpad.TAG,
                    Dpad.TAG_ARROW_KEYS ->
                        if (data.size >= 12)
                            for (i in profile.dpadArray.indices)
                                if (profile.dpadArray[i] == null) {
                                    profile.dpadArray[i] = Dpad(data)
                                    break
                                }

                    MOUSE_RIGHT -> if (data.size >= 3) {
                        profile.rightClick = Key()
                        profile.rightClick!!.x = data[1].toFloat()
                        profile.rightClick!!.y = data[2].toFloat()
                    }

                    "APPLICATION" -> if (data.size >= 2)
                        profile.packageName = data[1]

                    "ENABLED" ->
                        profile.disabled = false

                    "SCREENSIZE" -> if (data.size >= 3) {
                        profile.xRes = data[1].toInt()
                        profile.yRes = data[2].toInt()
                    }

                    MacroIdUtils.TAG ->
                        if (data.size >= 3) {
                            val macroId = data[1]
                            val macro = Macro()
                            macro.triggerKey = data[2]
                            profile.macroIdMap[macroId] = macro
                        }

                    SwipeKey.TAG ->
                        if (data.size > 6) profile.swipeKeys.add(SwipeKey(data))

                    ShiftKey.TAG ->
                        if (data.size > 6) profile.shiftKeys.add(ShiftKey(data))

                    Camera.TAG ->
                        if (data.size >= 4)
                            profile.camera = Camera(data)

                    MouseWalk.TAG ->
                        if (data.size == 4)
                            profile.mouseWalk = MouseWalk(data)

                    else -> {
                        if (data.size == 4 && data[0].startsWith("KEY_")) {
                            val key = Key(data)
                            profile.keys.add(key)
                        }
                    }
                }
            }
            return profile
        }
    }
}
