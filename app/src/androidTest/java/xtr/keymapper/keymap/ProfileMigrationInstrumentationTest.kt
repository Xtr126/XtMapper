package xtr.keymapper.keymap

import android.content.Context
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationInstrumentationTest {
    @Test
    fun migrationLeavesSourceAndSelectionIntactAndKeepsProvenanceAcrossBinder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("profile-key-migration-test", Context.MODE_PRIVATE)
        val profiles = KeymapProfiles(context, prefs)
        val source = setOf("KEY_A 10 20 0", "APPLICATION xtr.keymapper.test", "SCREENSIZE 800 600", "ENABLED")
        prefs.edit().clear()
            .putStringSet("Original", source)
            .putString(KeymapProfiles.KEY_LAST_SELECTED_PROFILE, "Original")
            .putString("designated_profile_xtr.keymapper.test", "Original")
            .commit()
        try {
            val migration = ProfileKeyMigration(source, ProfileShortcuts(emptyMap()))
            assertFalse(profiles.profileExists("Waydroid copy"))
            assertTrue(migration.bind(0, "KEY_Q"))
            val copy = checkNotNull(migration.buildCopy())
            assertFalse(profiles.saveMigrationCopy("Original", source, "Original", copy))
            assertTrue(profiles.saveMigrationCopy("Original", source, "Waydroid copy", copy))
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
                assertEquals(1234, parcel.readInt())
            } finally {
                parcel.recycle()
            }
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
