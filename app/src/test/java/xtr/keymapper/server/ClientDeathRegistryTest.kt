package xtr.keymapper.server

import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientDeathRegistryTest {
    private val registry = ClientDeathRegistry<String>()

    @Test
    fun `first registration of a client supersedes nothing and dies exactly once`() {
        val recipient = IBinder.DeathRecipient {}

        assertNull(registry.register("binder-x", recipient))
        assertTrue(registry.onDeath("binder-x", recipient))
        assertFalse(registry.onDeath("binder-x", recipient))
    }

    @Test
    fun `same-binder restart keeps the replacement registration liable for later death`() {
        val oldRecipient = IBinder.DeathRecipient {}
        val replacementRecipient = IBinder.DeathRecipient {}

        assertNull(registry.register("binder-x", oldRecipient))
        assertEquals(oldRecipient, registry.register("binder-x", replacementRecipient))

        assertFalse(registry.onDeath("binder-x", oldRecipient))
        assertTrue(registry.isCurrent("binder-x", replacementRecipient))
        assertTrue(registry.onDeath("binder-x", replacementRecipient))
        assertFalse(registry.onDeath("binder-x", replacementRecipient))
    }

    @Test
    fun `death cleanup before a queued startup rejects the stale request`() {
        val recipient = IBinder.DeathRecipient {}
        registry.register("binder-x", recipient)

        // Models the death-recipient cleanup winning the Handler ordering race.
        assertTrue(registry.onDeath("binder-x", recipient))

        var serviceConstructed = false
        val admitted = registry.runIfCurrent(
            "binder-x",
            recipient,
            isAlive = { true }
        ) {
            serviceConstructed = true
            true
        }

        assertFalse(admitted)
        assertFalse(serviceConstructed)
    }

    @Test
    fun `a binder already dead at admission cannot construct or install a service`() {
        val recipient = IBinder.DeathRecipient {}
        registry.register("binder-x", recipient)

        var serviceConstructed = false
        val admitted = registry.runIfCurrent(
            "binder-x",
            recipient,
            isAlive = { false }
        ) {
            serviceConstructed = true
            true
        }

        assertFalse(admitted)
        assertFalse(serviceConstructed)
    }

    @Test
    fun `a live replacement registration can pass startup admission`() {
        val oldRecipient = IBinder.DeathRecipient {}
        val replacementRecipient = IBinder.DeathRecipient {}
        registry.register("binder-x", oldRecipient)
        registry.register("binder-x", replacementRecipient)

        var installed = false
        val admitted = registry.runIfCurrent(
            "binder-x",
            replacementRecipient,
            isAlive = { true }
        ) {
            installed = true
            true
        }

        assertTrue(admitted)
        assertTrue(installed)
    }

    @Test
    fun `a death notification cannot interleave inside the startup admission decision`() {
        val recipient = IBinder.DeathRecipient {}
        registry.register("binder-x", recipient)

        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val deathAttempted = CountDownLatch(1)
        val deathCompleted = CountDownLatch(1)
        val installed = AtomicBoolean(false)
        val deathConsumed = AtomicBoolean(false)
        val admissionSucceeded = AtomicBoolean(false)

        val startupThread = Thread {
            admissionSucceeded.set(
                registry.runIfCurrent(
                    "binder-x",
                    recipient,
                    isAlive = { true }
                ) {
                    admissionEntered.countDown()
                    if (!releaseAdmission.await(5, TimeUnit.SECONDS)) return@runIfCurrent false
                    installed.set(true)
                    true
                }
            )
        }
        startupThread.start()
        assertTrue(admissionEntered.await(5, TimeUnit.SECONDS))

        val deathThread = Thread {
            deathAttempted.countDown()
            deathConsumed.set(registry.onDeath("binder-x", recipient))
            deathCompleted.countDown()
        }
        deathThread.start()
        assertTrue(deathAttempted.await(5, TimeUnit.SECONDS))

        // The death recipient must wait until the guarded installation decision ends.
        assertFalse(deathCompleted.await(50, TimeUnit.MILLISECONDS))
        releaseAdmission.countDown()

        startupThread.join(5000)
        deathThread.join(5000)

        assertFalse(startupThread.isAlive)
        assertFalse(deathThread.isAlive)
        assertTrue(admissionSucceeded.get())
        assertTrue(installed.get())
        assertTrue(deathConsumed.get())
    }

    @Test
    fun `failed link restores the previous live registration`() {
        val oldRecipient = IBinder.DeathRecipient {}
        val replacementRecipient = IBinder.DeathRecipient {}
        registry.register("binder-x", oldRecipient)

        val failure = IllegalStateException("binder died during link")
        try {
            registry.registerAndLink("binder-x", replacementRecipient) {
                throw failure
            }
        } catch (actual: IllegalStateException) {
            assertEquals(failure, actual)
        }

        assertTrue(registry.isCurrent("binder-x", oldRecipient))
        assertFalse(registry.isCurrent("binder-x", replacementRecipient))
    }

    @Test
    fun `clients are independent and unknown clients carry no registration`() {
        val recipientA = IBinder.DeathRecipient {}
        val supersededA = IBinder.DeathRecipient {}
        val recipientX = IBinder.DeathRecipient {}

        assertNull(registry.register("client-a", recipientA))
        assertNull(registry.register("client-x", recipientX))
        assertEquals(recipientA, registry.register("client-a", supersededA))

        assertFalse(registry.onDeath("client-a", recipientA))
        assertTrue(registry.onDeath("client-a", supersededA))
        assertTrue(registry.onDeath("client-x", recipientX))
        assertFalse(registry.onDeath("unknown-client", recipientX))
    }
}
