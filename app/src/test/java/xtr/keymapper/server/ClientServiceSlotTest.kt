package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientServiceSlotTest {
    private val slot = ClientServiceSlot<String, String>()

    @Test
    fun `an empty slot claims cleanly and stays empty`() {
        assertNull(slot.currentOwner())
        assertNull(slot.get())

        assertEquals(null to false, slot.claim("session-a"))
        assertNull(slot.get())
        assertNull(slot.currentOwner())
    }

    @Test
    fun `a superseded notification cannot clear or claim a replacement's installation`() {
        slot.install("session-a", "service-a")
        // The paused A-notification resumes after the Handler already installed B.
        slot.install("session-b", "service-b")

        val (claimed, superseded) = slot.claim("session-a")
        assertNull(claimed)
        assertTrue(superseded)

        // The replacement owns the slot untouched, and its own death still completes.
        assertEquals("service-b", slot.get())
        assertFalse(slot.claim("session-b").second)
        assertNull(slot.get())
    }

    @Test
    fun `a claimed session stops owning the slot so a replacement installs and is gated`() {
        slot.install("session-a", "service-a")

        // A's death claim clears its own installation in the same critical section.
        val (claimed, superseded) = slot.claim("session-a")
        assertEquals("service-a", claimed)
        assertFalse(superseded)
        assertNull(slot.get())

        // B installs while the dead session's shutdown is still running; the death-owner
        // gate in stopServer then sees the newer session and must not act on it.
        slot.install("session-b", "service-b")
        assertEquals("session-b", slot.currentOwner())
        assertNull(slot.claim("session-a").first)
        assertEquals("service-b", slot.get())
    }

    @Test
    fun `a committed shutdown rejects admission until its owner releases the terminal state`() {
        slot.install("session-a", "service-a")

        // The shutdown decision commits in the same critical section as the ownership
        // decision, so a replacement arriving after the commit is not admitted.
        val (claimed, superseded) = slot.claimForShutdown("session-a")
        assertEquals("service-a", claimed)
        assertFalse(superseded)

        assertFalse(slot.install("session-b", "service-b"))
        assertNull(slot.get())
        assertNull(slot.currentOwner())

        // After the shutdown's side effects complete (release), admission reopens.
        slot.releaseTerminal("session-a")
        assertTrue(slot.install("session-c", "service-c"))
        assertEquals("service-c", slot.get())
    }

    @Test
    fun `a superseded shutdown commits nothing and leaves the newer session admissible`() {
        slot.install("session-b", "service-b")

        val (claimed, superseded) = slot.claimForShutdown("session-a")
        assertNull(claimed)
        assertTrue(superseded)

        // No terminal state was latched: B stays installed and re-admission by B works.
        assertEquals("session-b", slot.currentOwner())
        assertTrue(slot.install("session-b", "service-b"))
        assertEquals("service-b", slot.claimForShutdown("session-b").first)
    }
}
