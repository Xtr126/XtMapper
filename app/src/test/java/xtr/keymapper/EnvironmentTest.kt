package xtr.keymapper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
