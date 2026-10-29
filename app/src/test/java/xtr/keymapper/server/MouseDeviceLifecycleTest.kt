package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseDeviceLifecycleTest {
    private val lifecycle = MouseDeviceLifecycle()

    @Test
    fun `device discovered before InputService is ready is requested when service becomes ready`() {
        assertNull(lifecycle.onDeviceDiscovered("/dev/input/event6"))

        val request = lifecycle.onInputServiceReady()

        assertEquals("/dev/input/event6", request?.device)
    }

    @Test
    fun `device discovered after InputService is ready is requested immediately`() {
        assertNull(lifecycle.onInputServiceReady())

        val request = lifecycle.onDeviceDiscovered("/dev/input/event6")

        assertEquals("/dev/input/event6", request?.device)
    }

    @Test
    fun `new InputService retries opening the previously discovered device`() {
        val device = "/dev/input/event6"
        lifecycle.onDeviceDiscovered(device)
        val firstServiceRequest = lifecycle.onInputServiceReady()!!

        assertTrue(lifecycle.onOpenResult(firstServiceRequest, success = true))
        assertNull(lifecycle.onDeviceDiscovered(device))

        lifecycle.onInputServiceStopped()
        val secondServiceRequest = lifecycle.onInputServiceReady()

        assertEquals(device, secondServiceRequest?.device)
        assertNotEquals(
            firstServiceRequest.serviceGeneration,
            secondServiceRequest?.serviceGeneration
        )
    }

    @Test
    fun `late result from an old InputService cannot mark the current service device as open`() {
        val device = "/dev/input/event6"
        lifecycle.onDeviceDiscovered(device)
        val oldRequest = lifecycle.onInputServiceReady()!!

        lifecycle.onInputServiceStopped()
        val currentRequest = lifecycle.onInputServiceReady()!!

        assertFalse(lifecycle.onOpenResult(oldRequest, success = true))
        assertTrue(lifecycle.onOpenResult(currentRequest, success = true))
        assertNull(lifecycle.onDeviceDiscovered(device))
    }

    @Test
    fun `blank device paths are ignored`() {
        assertNull(lifecycle.onDeviceDiscovered(""))
        assertNull(lifecycle.onInputServiceReady())
    }
}
