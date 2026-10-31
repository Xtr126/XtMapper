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

        // After the committed shutdown's side effects complete, admission reopens.
        slot.releaseTerminal()
        assertTrue(slot.install("session-b", "service-b"))
        assertEquals("service-b", slot.get())
    }

    @Test
    fun `a superseded notification cannot claim a replacement's installation`() {
        slot.install("session-a", "service-a")
        // The paused A-notification resumes after the Handler already installed B.
        slot.install("session-b", "service-b")

        val (claimed, superseded) = slot.claimForShutdown("session-a")
        assertNull(claimed)
        assertTrue(superseded)

        // The replacement owns the slot untouched, and its own death still completes.
        assertEquals("service-b", slot.get())
        assertFalse(slot.claimForShutdown("session-b").second)
        assertNull(slot.get())

        // B's shutdown was the only commitment: releasing it reopens admission.
        slot.releaseTerminal()
        assertTrue(slot.install("session-c", "service-c"))
    }

    @Test
    fun `a committed shutdown rejects admission until its release`() {
        slot.install("session-a", "service-a")

        // The shutdown decision commits in the same critical section as the ownership
        // decision, so a replacement arriving after the commit is not admitted.
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

        // A second notification claims the already-claimed slot: it stacks its own
        // commitment instead of overwriting A's, and it gains release permission only
        // for the commitment it recorded, never for A's.
        assertEquals(null to false, slot.claimForShutdown("session-b"))

        // The second shutdown completes and releases — admission stays rejected until
        // A's still-running cleanup releases its commitment too.
        slot.releaseTerminal()
        assertFalse(slot.install("session-c", "service-c"))

        slot.releaseTerminal()
        assertTrue(slot.install("session-c", "service-c"))
    }

    @Test
    fun `repeated claims from the same owner stack commitments that each release`() {
        slot.install("session-a", "service-a")
        assertEquals("service-a" to false, slot.claimForShutdown("session-a"))

        // The same client dies again while its first cleanup still runs: the repeated
        // claim stacks a second commitment and its release does not reopen admission
        // while the first cleanup has not released yet.
        assertEquals(null to false, slot.claimForShutdown("session-a"))
        assertFalse(slot.install("session-b", "service-b"))

        slot.releaseTerminal()
        assertFalse(slot.install("session-b", "service-b"))

        slot.releaseTerminal()
        assertTrue(slot.install("session-b", "service-b"))
    }
}
