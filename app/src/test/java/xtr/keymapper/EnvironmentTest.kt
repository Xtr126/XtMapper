package xtr.keymapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xtr.keymapper.keymap.KeymapConfig

class EnvironmentTest {
    @Test
    fun `waydroid identity does not depend on a capture backend`() {
        val properties = mapOf(
            "waydroid.tools_version" to "2.0.0",
            "waydroid.xdg_runtime_dir" to "/run/xdg",
        )
        assertTrue(Environment.detectWaydroid(properties::get))
    }

    @Test
    fun `one property and generic Mesa hardware are insufficient`() {
        val properties = mapOf(
            "waydroid.tools_version" to "2.0.0",
            "waydroid.xdg_runtime_dir" to "  ",
            "waydroid.wayland_display" to "None",
            "ro.hardware.egl" to "mesa",
        )
        assertFalse(Environment.detectWaydroid(properties::get))
        assertFalse(Environment.detectWaydroid { null })
    }

    @Test
    fun `stored mode takes precedence over the legacy preference`() {
        assertEquals(KeymapConfig.MODE_ROOT, KeymapConfig.resolveInputMode(KeymapConfig.MODE_ROOT, true))
        assertEquals(KeymapConfig.MODE_SHIZUKU, KeymapConfig.resolveInputMode(KeymapConfig.MODE_SHIZUKU, false))
        assertEquals(KeymapConfig.MODE_WAYDROID, KeymapConfig.resolveInputMode(KeymapConfig.MODE_WAYDROID, true))
        assertEquals(KeymapConfig.MODE_SHIZUKU, KeymapConfig.resolveInputMode(-1, true))
        assertEquals(KeymapConfig.MODE_ROOT, KeymapConfig.resolveInputMode(-1, false))
    }
}
