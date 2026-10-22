package xtr.keymapper.keymap

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
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
        // ProfileDisabled is disabled, so it should fall back to ProfileEnabled
        assertEquals("ProfileEnabled", result)
    }

    @Test
    fun testGetDesignatedProfileReturnsNullWhenNoProfilesForApp() {
        val initialData = mutableMapOf<String, Any?>()
        val (context, prefs) = createFakeContextAndPrefs(initialData)
        val keymapProfiles = KeymapProfiles(context, prefs)

        assertNull(keymapProfiles.getDesignatedProfileForApp("com.unknown.app"))
    }
}
