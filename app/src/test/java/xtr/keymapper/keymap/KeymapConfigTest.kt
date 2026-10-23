package xtr.keymapper.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapConfigTest {

    @Test
    fun testRootModeAndBlockKeyboardInputDefaults() {
        val config = KeymapConfig(null)
        config.inputMode = KeymapConfig.MODE_ROOT
        config.useShizuku = false

        assertTrue(config.isRootMode)
        assertFalse(config.isShizukuMode)
        assertFalse(config.blockKeyboardInput)

        // Enable blocker in root mode
        config.blockKeyboardInput = true
        assertTrue(config.blockKeyboardInput)
        assertTrue(config.isRootMode)
    }

    @Test
    fun testShizukuModeDetection() {
        val config = KeymapConfig(null)
        config.inputMode = KeymapConfig.MODE_SHIZUKU
        assertTrue(config.isShizukuMode)
        assertFalse(config.isRootMode)

        config.inputMode = KeymapConfig.MODE_ROOT
        config.useShizuku = true
        assertTrue(config.isShizukuMode)
        assertFalse(config.isRootMode)
    }

    @Test
    fun testBlockKeyboardShortcutDefaults() {
        val config = KeymapConfig(null)
        assertEquals("KEY_B", config.blockKeyboardShortcutKey)
        // Default modifier when loaded via SharedPreferences is KEY_CTRL
    }
}
