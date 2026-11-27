package xtr.keymapper.keymap

import android.content.Context
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProfileMigrationInstrumentationTest {
    @Test
    fun migrationLeavesSourceAndSelectionIntactAndKeepsProvenanceAcrossBinder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "profile-key-migration-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val profiles = KeymapProfiles(context, prefs)
        val metadata = setOf("APPLICATION xtr.keymapper.test", "SCREENSIZE 800 600", "ENABLED")
        val source = metadata + setOf("KEY_A 10 20 0", "KEY_SEMANTICS 1 LEGACY_CAGE")
        prefs.edit().clear()
            .putStringSet("Original", source)
            .putString(KeymapProfiles.KEY_LAST_SELECTED_PROFILE, "Original")
            .putString("designated_profile_xtr.keymapper.test", "Original")
            .commit()
        try {
            val shortcuts = ProfileShortcuts(mapOf(ProfileShortcut.EDITOR to "KEY_A"))
            assertFalse(profiles.getProfile("Original", false).isNativeWaylandReady())
            val cancelled = ProfileKeyMigration(source, shortcuts)
            assertTrue(cancelled.bind(0, "KEY_Q"))
            assertNull(cancelled.buildCopy())
            assertFalse(profiles.profileExists("Waydroid copy"))
            assertEquals(source, prefs.getStringSet("Original", null))

            // Physical Q emits logical A through Cage on AZERTY.
            val migration = ProfileKeyMigration(source, shortcuts)
            assertTrue(migration.bind(0, checkNotNull(ProfileKeySemantics.physicalKey(16))))
            assertTrue(migration.bind(1, "KEY_Q"))
            val copy = checkNotNull(migration.buildCopy())
            assertFalse(profiles.saveMigrationCopy("Original", source, "Original", copy))
            assertTrue(profiles.saveMigrationCopy("Original", source, "Waydroid copy", copy))
            assertTrue(copy.containsAll(metadata))

            // The same legacy label keeps the physical A position on a US layout.
            val usMigration = ProfileKeyMigration(source, shortcuts)
            assertTrue(usMigration.bind(0, checkNotNull(ProfileKeySemantics.physicalKey(30))))
            assertTrue(usMigration.bind(1, "KEY_A"))
            assertTrue(profiles.saveMigrationCopy("Original", source, "US copy", checkNotNull(usMigration.buildCopy())))
            val us = profiles.getProfile("US copy", false)
            assertTrue(us.isNativeWaylandReady())
            assertEquals("KEY_A", us.keys.single().code)
            assertEquals("KEY_A", us.waydroidShortcuts?.get(ProfileShortcut.EDITOR))
            assertEquals(source, prefs.getStringSet("Original", null))
            assertEquals("Original", profiles.getLastSelectedProfile())
            assertEquals("Original", profiles.getDesignatedProfileForApp("xtr.keymapper.test"))
            profiles.setProfileEnabled("Waydroid copy", false)
            assertEquals(copy - "ENABLED", prefs.getStringSet("Waydroid copy", null))
            val saved = profiles.getProfile("Waydroid copy", false)
            val parcel = Parcel.obtain()
            try {
                saved.writeToParcel(parcel, 0)
                parcel.writeInt(1234)
                parcel.setDataPosition(0)
                val restored = KeymapProfile.CREATOR.createFromParcel(parcel)
                assertTrue(restored.isNativeWaylandReady())
                assertEquals("KEY_Q", restored.keys.single().code)
                assertEquals("KEY_Q", restored.waydroidShortcuts?.get(ProfileShortcut.EDITOR))
                assertTrue(restored.disabled)
                assertEquals(1234, parcel.readInt())
            } finally {
                parcel.recycle()
            }
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
    }
}
