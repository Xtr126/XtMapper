package xtr.keymapper.keymap

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class KeymapProfilesTest {

    private fun createFakeContextAndPrefs(initialData: MutableMap<String, Any?> = mutableMapOf()): Pair<Context, SharedPreferences> {
        val storage = initialData

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    storage[args[0] as String] = args[1]
                    proxy
                }
                "putStringSet" -> {
                    storage[args[0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    storage.remove(args[0] as String)
                    proxy
                }
                "apply", "commit" -> {
                    true
                }
                else -> proxy
            }
        } as SharedPreferences.Editor

        val sharedPreferencesProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> HashMap(storage)
                "getString" -> {
                    val key = args[0] as String
                    val def = args.getOrNull(1) as? String
                    (storage[key] as? String) ?: def
                }
                "getStringSet" -> {
                    val key = args[0] as String
                    val def = args.getOrNull(1) as? Set<String>
                    @Suppress("UNCHECKED_CAST")
                    (storage[key] as? Set<String>) ?: def
                }
                "contains" -> storage.containsKey(args[0] as String)
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences

        val fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return sharedPreferencesProxy
            }
        }

        return Pair(fakeContext, sharedPreferencesProxy)
    }

    @Test
    fun togglesDefaultWithoutAnAppAndPreservesItsContents() {
        val original = setOf("APPLICATION ", "SCREENSIZE 1080 1920", "KEY_A 100 100")
        val data = mutableMapOf<String, Any?>("Default" to original)
        val (context, prefs) = createFakeContextAndPrefs(data)
        val profiles = KeymapProfiles(context, prefs)

        profiles.setProfileEnabled("Default", true)
        assertTrue(!profiles.getProfile("Default", false).disabled)
        assertEquals(original + "ENABLED", data["Default"])
        profiles.setProfileEnabled("Default", false)
        assertTrue(profiles.getProfile("Default", false).disabled)
        assertEquals(original, data["Default"])
    }

    @Test
    fun togglingOneProfileDoesNotChangeOtherProfilesForTheSameApp() {
        val lines = setOf("APPLICATION com.example.game", "ENABLED")
        val data = mutableMapOf<String, Any?>("First" to lines, "Second" to lines)
        val (context, prefs) = createFakeContextAndPrefs(data)
        KeymapProfiles(context, prefs).setProfileEnabled("First", false)
        assertEquals(lines - "ENABLED", data["First"])
        assertEquals(lines, data["Second"])
    }

    @Test
    fun malformedShiftKeyRecordsAreSkippedWithoutFailingProfileParse() {
        val profile = KeymapProfiles.parse(setOf(
            "SHIFT_KEY A 100 200 B 300 400",
            "SHIFT_KEY A NaN 200 B 300 400",
            "SHIFT_KEY A 100 200 B 300 400 not-a-duration",
            "SHIFT_KEY  100 200 B 300 400",
            "SHIFT_KEY A 100 200 B 300 400 extra"
        ))

        assertEquals(1, profile.shiftKeys.size)
        assertEquals("A", profile.shiftKeys.single().key1.code)
        assertEquals("B", profile.shiftKeys.single().key2.code)
    }

    @Test
    fun testSetProfileEnabledDoesNotThrowClassCastExceptionWithDesignatedProfileEntries() {
        val initialData = mutableMapOf<String, Any?>(
            "Default" to setOf("APPLICATION com.example.game", "ENABLED", "KEY_A 100 100"),
            "AltProfile" to setOf("APPLICATION com.example.game", "KEY_B 200 200"),
            "designated_profile_com.example.game" to "Default" // Stored as String, NOT Set<String>
        )

        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        // Prior to fix, this would throw ClassCastException: java.lang.String cannot be cast to java.util.Set
        keymapProfiles.setProfileEnabled("Default", false)

        val updatedDefault = initialData["Default"] as? Set<*>
        assertTrue(updatedDefault != null)
        assertTrue(!updatedDefault!!.contains("ENABLED"))
    }

    @Test
    fun testGetDesignatedProfileReturnsValidEnabledProfile() {
        val initialData = mutableMapOf<String, Any?>(
            "Profile1" to setOf("APPLICATION com.example.game", "ENABLED"),
            "Profile2" to setOf("APPLICATION com.example.game"),
            "designated_profile_com.example.game" to "Profile1"
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        val result = keymapProfiles.getDesignatedProfileForApp("com.example.game")
        assertEquals("Profile1", result)
    }

    @Test
    fun testGetDesignatedProfileIgnoresStaleAssignmentBelongingToDifferentApp() {
        val initialData = mutableMapOf<String, Any?>(
            "ProfileA" to setOf("APPLICATION com.other.app", "ENABLED"),
            "ProfileB" to setOf("APPLICATION com.example.game", "ENABLED"),
            "designated_profile_com.example.game" to "ProfileA" // Stale: points to ProfileA which belongs to com.other.app
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        val result = keymapProfiles.getDesignatedProfileForApp("com.example.game")
        // Should ignore ProfileA and select ProfileB
        assertEquals("ProfileB", result)
    }

    @Test
    fun testGetDesignatedProfileFallsBackToEnabledProfileWhenDesignatedIsDisabled() {
        val initialData = mutableMapOf<String, Any?>(
            "ProfileDisabled" to setOf("APPLICATION com.example.game"),
            "ProfileEnabled" to setOf("APPLICATION com.example.game", "ENABLED"),
            "designated_profile_com.example.game" to "ProfileDisabled"
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        val result = keymapProfiles.getDesignatedProfileForApp("com.example.game")
        // ProfileDisabled is disabled, so it should fall back for this launch.
        assertEquals("ProfileEnabled", result)
        // Keep the stored choice so re-enabling it restores the user's designation.
        assertEquals("ProfileDisabled", prefs.getString("designated_profile_com.example.game", null))
    }

    @Test
    fun testGetDesignatedProfileRepairsStaleAssignment() {
        val initialData = mutableMapOf<String, Any?>(
            "Profile1" to setOf("APPLICATION com.example.game", "ENABLED"),
            "Profile2" to setOf("APPLICATION com.example.game"),
            "designated_profile_com.example.game" to "DeletedProfile"
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        assertEquals("Profile1", keymapProfiles.getDesignatedProfileForApp("com.example.game"))
        assertEquals("Profile1", prefs.getString("designated_profile_com.example.game", null))
    }

    @Test
    fun testGetDesignatedProfileReturnsNullWhenNoProfilesForApp() {
        val initialData = mutableMapOf<String, Any?>(
            "designated_profile_com.unknown.app" to "DeletedProfile"
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        assertNull(keymapProfiles.getDesignatedProfileForApp("com.unknown.app"))
        assertNull(prefs.getString("designated_profile_com.unknown.app", null))
    }

    @Test
    fun testLastSelectedProfilePersistenceAndFallback() {
        val initialData = mutableMapOf<String, Any?>(
            "SingleProfile" to setOf("APPLICATION com.example.game", "ENABLED")
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        // Initial fallback when none selected
        assertEquals("SingleProfile", keymapProfiles.getLastSelectedProfile())

        // Set last selected profile
        initialData["Profile2"] = setOf("APPLICATION com.example.game", "ENABLED")
        keymapProfiles.setLastSelectedProfile("Profile2")
        assertEquals("Profile2", keymapProfiles.getLastSelectedProfile())

        // If saved profile doesn't exist, fall back to first available
        keymapProfiles.setLastSelectedProfile("NonExistent")
        assertTrue(keymapProfiles.getLastSelectedProfile() in setOf("SingleProfile", "Profile2"))

        // When no profiles exist, fall back to Default
        initialData.clear()
        assertEquals("Default", keymapProfiles.getLastSelectedProfile())
    }

    @Test
    fun profileCollidingWithMetadataKeyIsMigratedWithoutLosingData() {
        val colliding = setOf("APPLICATION com.example.game", "ENABLED")
        val initialData = mutableMapOf<String, Any?>(
            KeymapProfiles.KEY_LAST_SELECTED_PROFILE to colliding,
            "designated_profile_com.example.game" to KeymapProfiles.KEY_LAST_SELECTED_PROFILE,
            "SafeProfile" to setOf("APPLICATION com.example.game", "ENABLED")
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        val selected = keymapProfiles.getLastSelectedProfile()

        // The reserved key is a plain metadata string again, naming the recovered profile.
        val recoveredName = prefs.getString(KeymapProfiles.KEY_LAST_SELECTED_PROFILE, null)
        assertNotNull(recoveredName)
        assertEquals(recoveredName, selected)
        // The colliding profile survived under its unique key and is listed again.
        assertEquals(colliding, prefs.getStringSet(recoveredName!!, null))
        assertTrue(keymapProfiles.getAllProfiles().containsKey(recoveredName))
        // Designations that pointed at the reserved name follow the migration.
        assertEquals(recoveredName, prefs.getString("designated_profile_com.example.game", null))
    }

    @Test
    fun testRenameProfileUpdatesLastSelectedProfile() {
        val initialData = mutableMapOf<String, Any?>(
            "OldName" to setOf("APPLICATION com.example.game", "ENABLED"),
            KeymapProfiles.KEY_LAST_SELECTED_PROFILE to "OldName",
            "designated_profile_com.example.game" to "OldName"
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        keymapProfiles.renameProfile("OldName", "NewName")
        assertEquals("NewName", keymapProfiles.getLastSelectedProfile())
        assertEquals("NewName", prefs.getString("designated_profile_com.example.game", null))
    }

    @Test
    fun testDeleteProfileClearsLastSelectedProfile() {
        val initialData = mutableMapOf<String, Any?>(
            "ProfileToDelete" to setOf("APPLICATION com.example.game", "ENABLED"),
            "OtherProfile" to setOf("APPLICATION com.example.game", "ENABLED"),
            KeymapProfiles.KEY_LAST_SELECTED_PROFILE to "ProfileToDelete",
            "designated_profile_com.example.game" to "ProfileToDelete"
        )
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        keymapProfiles.deleteProfile("ProfileToDelete")
        // After deletion of last selected, falls back to remaining profile
        assertEquals("OtherProfile", keymapProfiles.getLastSelectedProfile())
        assertNull(prefs.getString("designated_profile_com.example.game", null))
    }
}
