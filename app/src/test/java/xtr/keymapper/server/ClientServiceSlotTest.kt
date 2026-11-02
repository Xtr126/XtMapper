package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    fun `owner reports the installed session and clears on release`() {
        assertNull(slot.owner())
        slot.install("session-a", "service-a")
        assertEquals("session-a", slot.owner())
        assertEquals("service-a", slot.get())
    }

    @Test
    fun `defer returns immediately when admission is already open`() {
        var retried = false
        assertFalse(slot.deferUntilAvailable { retried = true })
        assertFalse(retried)
        assertTrue(slot.install("session-a", "service-a"))
    }

    @Test
    fun `runIfOwner runs only while the exact session still occupies the slot`() {
        assertFalse(
            "no owner installed yet",
            slot.runIfOwner("session-a") { throw AssertionError("must not run") },
        )

        slot.install("session-a", "service-a")
        var invocations = 0
        assertTrue(slot.runIfOwner("session-a") { invocations++ })
        assertEquals(1, invocations)

        assertFalse("a different owner must not run another owner's block", slot.runIfOwner("session-b") { invocations++ })
        assertEquals(1, invocations)

        assertEquals("service-a" to false, slot.claimForShutdown("session-a"))
        assertFalse("a committed shutdown revokes pending side effects", slot.runIfOwner("session-a") { invocations++ })
        assertEquals(1, invocations)
    }

    @Test
    fun `a claim completes only after the running ownership block returns`() {
        slot.install("session-a", "service-a")
        val blockRunning = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val claimOutcome = AtomicReference<Pair<String?, Boolean>?>(null)

        val owner = Thread {
            val decided = slot.runIfOwner("session-a") {
                blockRunning.countDown()
                assertTrue("block released in time", releaseBlock.await(2, TimeUnit.SECONDS))
            }
            assertTrue("ownership block ran", decided)
        }
        owner.isDaemon = true
        owner.start()
        assertTrue("block must be entered", blockRunning.await(2, TimeUnit.SECONDS))

        // Shutdown can only enter the shared slot monitor after the block returns;
        // a session teardown can therefore never overtake a pending side effect.
        val claimer = Thread { claimOutcome.set(slot.claimForShutdown("session-a")) }
        claimer.isDaemon = true
        claimer.start()
        releaseBlock.countDown()

        owner.join(2_000)
        claimer.join(2_000)
        assertFalse("owner run finished", owner.isAlive)
        assertFalse("claimer finished", claimer.isAlive)
        assertEquals("shutdown revokes only after the block returned", "service-a" to false, claimOutcome.get())
        assertNull(slot.get())
    }
}
