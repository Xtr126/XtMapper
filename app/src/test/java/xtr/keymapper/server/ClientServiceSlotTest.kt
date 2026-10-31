package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientServiceSlotTest {
    private val slot = ClientServiceSlot<String, String>()

    @Test
    fun `an empty slot claims cleanly and admission reopens after the release`() {
        assertNull(slot.get())

        assertEquals(null to false, slot.claimForShutdown("session-a"))
        assertNull(slot.get())
        assertFalse(slot.install("session-b", "service-b"))

        slot.releaseTerminal()
        assertTrue(slot.install("session-b", "service-b"))
        assertEquals("service-b", slot.get())
    }

    @Test
    fun `a superseded notification cannot claim a replacement's installation`() {
        slot.install("session-a", "service-a")
        slot.install("session-b", "service-b")

        val (claimed, superseded) = slot.claimForShutdown("session-a")
        assertNull(claimed)
        assertTrue(superseded)

        assertEquals("service-b", slot.get())
        assertFalse(slot.claimForShutdown("session-b").second)
        assertNull(slot.get())

        slot.releaseTerminal()
        assertTrue(slot.install("session-c", "service-c"))
    }

    @Test
    fun `a committed shutdown rejects admission until its release`() {
        slot.install("session-a", "service-a")

        val (claimed, superseded) = slot.claimForShutdown("session-a")
        assertEquals("service-a", claimed)
        assertFalse(superseded)

        assertFalse(slot.install("session-b", "service-b"))
        assertNull(slot.get())

        slot.releaseTerminal()
        assertTrue(slot.install("session-c", "service-c"))
    }

    @Test
    fun `an overlapping claim does not shorten an active shutdown commitment`() {
        slot.install("session-a", "service-a")
        slot.claimForShutdown("session-a")
        assertEquals(null to false, slot.claimForShutdown("session-b"))

        slot.releaseTerminal()
        assertFalse(slot.install("session-c", "service-c"))

        slot.releaseTerminal()
        assertTrue(slot.install("session-c", "service-c"))
    }

    @Test
    fun `repeated claims from the same owner stack commitments that each release`() {
        slot.install("session-a", "service-a")
        assertEquals("service-a" to false, slot.claimForShutdown("session-a"))
        assertEquals(null to false, slot.claimForShutdown("session-a"))
        assertFalse(slot.install("session-b", "service-b"))

        slot.releaseTerminal()
        assertFalse(slot.install("session-b", "service-b"))

        slot.releaseTerminal()
        assertTrue(slot.install("session-b", "service-b"))
    }

    @Test
    fun `deferred starts resume only after the final shutdown commitment is released`() {
        slot.install("session-a", "service-a")
        assertEquals("service-a" to false, slot.claimForShutdown("session-a"))
        assertEquals(null to false, slot.claimForShutdown("session-b"))

        var retries = 0
        assertTrue(slot.deferUntilAvailable { retries++ })
        assertTrue(slot.deferUntilAvailable { retries++ })

        slot.releaseTerminal()
        assertEquals(0, retries)
        assertFalse(slot.install("session-c", "service-c"))

        slot.releaseTerminal()
        assertEquals(2, retries)
        assertTrue(slot.install("session-c", "service-c"))

        slot.releaseTerminal()
        assertEquals(2, retries)
    }

    @Test
    fun `defer returns immediately when admission is already open`() {
        var retried = false
        assertFalse(slot.deferUntilAvailable { retried = true })
        assertFalse(retried)
        assertTrue(slot.install("session-a", "service-a"))
    }
}
