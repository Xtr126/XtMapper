package xtr.keymapper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launch-context classification contract, exercised independently of Cage:
 * these tests apply the signal directly, exactly as RemoteServiceShell (args)
 * and MainActivity (intent extra) do. They prove XtMapper's reaction to a
 * supplied signal — they do NOT prove that Cage ever supplies one (the
 * Cage-side launch change is not implemented yet).
 */
class EnvironmentTest {

    @After
    fun resetSignal() {
        Environment.launchSignal = false
    }

    @Test
    fun `signal absent classifies as android`() {
        assertFalse(Environment.isWaydroid)
        assertTrue(Environment.isAndroid)
        assertEquals("android", Environment.name)
    }

    @Test
    fun `explicit false classifies as android`() {
        Environment.launchSignal = false
        assertFalse(Environment.isWaydroid)
        assertTrue(Environment.isAndroid)
        assertEquals("android", Environment.name)
    }

    @Test
    fun `signal present classifies as waydroid`() {
        Environment.launchSignal = true
        assertTrue(Environment.isWaydroid)
        assertFalse(Environment.isAndroid)
        assertEquals("waydroid", Environment.name)
    }

    @Test
    fun `signal applied after an android default reclassifies`() {
        // Mirrors the cold start order: App.onCreate sees the default, then
        // MainActivity applies the delivered extra.
        assertEquals("android", Environment.name)
        Environment.launchSignal = true
        assertEquals("waydroid", Environment.name)
    }

    @Test
    fun `signal token and intent extra match the agreed names`() {
        assertEquals("--cage-xtmapper-launch-signal", Environment.CLI_ARG)
        assertEquals("cage_xtmapper_launch_signal", Environment.INTENT_EXTRA)
    }
}
