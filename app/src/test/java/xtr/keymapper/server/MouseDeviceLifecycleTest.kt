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
    fun `failed open retries on the next discovery and in a new service generation`() {
        val device = "/dev/input/event6"
        lifecycle.onDeviceDiscovered(device)
        val firstRequest = lifecycle.onInputServiceReady()!!

        assertTrue(lifecycle.onOpenResult(firstRequest, success = false))
        val retryRequest = lifecycle.onDeviceDiscovered(device)!!
        assertEquals(device, retryRequest.device)
        assertEquals(firstRequest.serviceGeneration, retryRequest.serviceGeneration)
        assertNull(lifecycle.onDeviceDiscovered(device))

        lifecycle.onInputServiceStopped()
        assertEquals(device, lifecycle.onInputServiceReady()?.device)
    }

    @Test
    fun `open result after stop without replacement is rejected`() {
        val device = "/dev/input/event6"
        lifecycle.onDeviceDiscovered(device)
        val request = lifecycle.onInputServiceReady()!!

        lifecycle.onInputServiceStopped()

        assertFalse(lifecycle.onOpenResult(request, success = true))
        assertNull(lifecycle.onDeviceDiscovered(device))
    }

    @Test
    fun `open result for a superseded device is rejected`() {
        assertNull(lifecycle.onInputServiceReady())
        val oldRequest = lifecycle.onDeviceDiscovered("/dev/input/event6")!!
        val currentRequest = lifecycle.onDeviceDiscovered("/dev/input/event7")!!

        assertFalse(lifecycle.onOpenResult(oldRequest, success = true))
        assertTrue(lifecycle.onOpenResult(currentRequest, success = true))
    }

    @Test
    fun `blank device paths are ignored`() {
        assertNull(lifecycle.onDeviceDiscovered(""))
        assertNull(lifecycle.onInputServiceReady())
    }

    @Test
    fun `queued death notification from a replaced session is ignored and the replacement stays active`() {
        // RemoteService queues the replacement task and the death notification on one handler:
        // session A is active when A's client dies, the replacement session B installs while
        // A's queued cleanup task waits, and that task then runs last.
        lifecycle.onInputServiceReady()
        val sessionAtDeath = lifecycle.currentServiceGeneration()
        lifecycle.onInputServiceStopped()
        lifecycle.onInputServiceReady()

        assertTrue(lifecycle.isStaleClientDeath(sessionAtDeath))
        assertNotEquals(sessionAtDeath, lifecycle.currentServiceGeneration())
        assertEquals(2L, lifecycle.currentServiceGeneration())

        // The replacement session's own death still identifies the active session and
        // proceeds to cleanup.
        assertFalse(lifecycle.isStaleClientDeath(lifecycle.currentServiceGeneration()))

        // A notification captured while no session ran keeps the preexisting semantics.
        assertFalse(lifecycle.isStaleClientDeath(null))
    }

    @Test
    fun `death notification without a replacement session proceeds to cleanup`() {
        lifecycle.onInputServiceReady()
        val sessionAtDeath = lifecycle.currentServiceGeneration()

        // No replacement session followed, so the death cleanup is still meant for this one.
        assertFalse(lifecycle.isStaleClientDeath(sessionAtDeath))
        assertFalse(lifecycle.isStaleClientDeath(null))
    }
}
