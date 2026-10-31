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

        // After the committed shutdown's side effects complete, admission reopens.
        slot.releaseTerminal("session-a")
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

        slot.releaseTerminal("session-a")
        assertTrue(slot.install("session-c", "service-c"))
        assertEquals("service-c", slot.get())
    }
}
