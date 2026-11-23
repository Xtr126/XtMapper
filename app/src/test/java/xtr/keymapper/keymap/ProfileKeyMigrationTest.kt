package xtr.keymapper.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileKeyMigrationTest {
    @Test
    fun unversionedAndFutureProfilesRequireMigration() {
        val key = "KEY_A 10 20 0"
        val legacy = KeymapProfiles.parse(setOf(key))
        assertEquals(ProfileKeySemantics.UNKNOWN, legacy.keySemantics)
        assertFalse(legacy.isNativeWaylandReady())
        assertEquals(
            ProfileKeySemantics.LEGACY_CAGE,
            KeymapProfiles.parse(setOf(key, "KEY_SEMANTICS 1 LEGACY_CAGE")).keySemantics
        )
        assertEquals(
            ProfileKeySemantics.UNKNOWN,
            KeymapProfiles.parse(setOf(key, "KEY_SEMANTICS 2 PHYSICAL_EVDEV")).keySemantics
        )
        assertFalse(KeymapProfiles.parse(setOf(key, "KEY_SEMANTICS 1 PHYSICAL_EVDEV")).isNativeWaylandReady())
    }

    @Test
    fun azertyAndUsKeepIntendedPhysicalPosition() {
        val source = setOf("KEY_A 10 20 0", "APPLICATION xtr.keymapper.test", "ENABLED")
        val migration = ProfileKeyMigration(source, ProfileShortcuts(emptyMap()))
        assertNull(migration.buildCopy())
        assertFalse(migration.bind(0, "KEY_RESERVED"))
        assertFalse(migration.bind(0, "KEY_AZERTY"))

        // Cage's logical A is emitted by physical Q on AZERTY.
        assertTrue(migration.bind(0, ProfileKeySemantics.physicalKey(16)!!))
        val azertyCopy = KeymapProfiles.parse(migration.buildCopy())
        assertEquals("KEY_Q", azertyCopy.keys.single().code)
        assertTrue(azertyCopy.isNativeWaylandReady())
        assertEquals("KEY_A", KeymapProfiles.parse(source).keys.single().code)

        // Retry and keep physical A for a US keyboard.
        assertTrue(migration.bind(0, ProfileKeySemantics.physicalKey(30)!!))
        assertEquals("KEY_A", KeymapProfiles.parse(migration.buildCopy()).keys.single().code)
        assertNull(ProfileKeySemantics.physicalKey(0))
    }

    @Test
    fun rebindsEachKeyboardCategoryAndCopiesOtherLines() {
        val untouched = setOf(
            "APPLICATION xtr.keymapper.test",
            "SCREENSIZE 800 600",
            "MOUSE_RIGHT 520 210",
            "MOUSE_WALK 150 420 75",
            "FUTURE_METADATA 7"
        )
        val source = untouched + setOf(
            "KEY_A 10 20 0",
            "DPAD 1 2 3 4 5 6 7 KEY_W KEY_S KEY_A KEY_D",
            "SWIPE_KEY A 20 30 S 40 50",
            "SHIFT_KEY Q 25 35 E 45 55 150",
            "MACRO test F",
            "CAMERA 25 50 1.25 0.75 0 KEY_KEY_B"
        )
        val shortcutSnapshot = ProfileShortcuts(
            ProfileShortcut.entries.associateWith { if (it == ProfileShortcut.AIM) "KEY_MMB" else "KEY_A" }
        )
        val migration = ProfileKeyMigration(source, shortcutSnapshot)
        assertEquals(16, migration.bindings.size)
        migration.bindings.indices.forEach { assertTrue(migration.bind(it, "KEY_Z")) }

        val copy = migration.buildCopy()!!
        val parsed = KeymapProfiles.parse(copy)
        assertTrue(parsed.isNativeWaylandReady())
        assertTrue(parsed.disabled)
        assertTrue(copy.containsAll(untouched))
        assertTrue("DPAD 1 2 3 4 5 6 7 KEY_Z KEY_Z KEY_Z KEY_Z" in copy)
        assertTrue("SWIPE_KEY Z 20 30 Z 40 50" in copy)
        assertTrue("SHIFT_KEY Z 25 35 Z 45 55 150" in copy)
        assertTrue("MACRO test Z" in copy)
        assertTrue("CAMERA 25 50 1.25 0.75 0 KEY_Z" in copy)
        assertEquals(150, parsed.shiftKeys.single().durationMs)
        assertEquals("KEY_Z", parsed.camera?.triggerKeyCode)

        val config = KeymapConfig(null)
        parsed.waydroidShortcuts!!.applyTo(config)
        assertEquals("KEY_Z", config.pauseResumeShortcutKey)
        assertEquals("KEY_Z", config.launchEditorShortcutKey)
        assertEquals("KEY_Z", config.switchProfileShortcutKey)
        assertEquals("KEY_Z", config.blockKeyboardShortcutKey)
        assertEquals("KEY_MMB", config.mouseAimShortcutKey)
        assertEquals("KEY_Z", config.graveMouseAimShortcutKey)
    }

    @Test
    fun migratesImplicitCameraAndLeavesUnboundMacroUnbound() {
        val source = setOf("CAMERA 10 20 1.0 1.0 1", "MACRO unbound null")
        val migration = ProfileKeyMigration(source, ProfileShortcuts(emptyMap()))
        assertEquals("KEY_ALT", migration.bindings.single().original)
        assertTrue(migration.bind(0, "KEY_LEFTALT"))
        val copy = migration.buildCopy()!!
        assertTrue("MACRO unbound null" in copy)
        assertEquals("KEY_LEFTALT", KeymapProfiles.parse(copy).camera?.triggerKeyCode)
        assertTrue(KeymapProfiles.parse(copy).isNativeWaylandReady())
    }
}
