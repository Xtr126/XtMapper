package xtr.keymapper.server

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientDeathRegistryTest {
    private val registry = ClientDeathRegistry<String>()

    @Test
    fun `first registration of a client supersedes nothing and dies exactly once`() {
        val recipient = IBinder.DeathRecipient {}

        assertNull(registry.register("binder-x", recipient))
        assertEquals(recipient, registry.onDeath("binder-x"))
        assertNull(registry.onDeath("binder-x"))
    }

    @Test
    fun `same-binder restart keeps the replacement registration liable for later death`() {
        val oldRecipient = IBinder.DeathRecipient {}
        val replacementRecipient = IBinder.DeathRecipient {}

        assertNull(registry.register("binder-x", oldRecipient))
        // The restart supersedes the old registration and returns it for unlinking.
        assertEquals(oldRecipient, registry.register("binder-x", replacementRecipient))

        // Service replacement cleanup never contacts the registry, so completing the
        // replacement leaves the replacement's registration in place: the client's own
        // later death still finds it, and a second notification finds nothing.
        assertEquals(replacementRecipient, registry.onDeath("binder-x"))
        assertNull(registry.onDeath("binder-x"))
    }

    @Test
    fun `clients are independent and unknown clients carry no registration`() {
        val recipientA = IBinder.DeathRecipient {}
        val supersededA = IBinder.DeathRecipient {}
        val recipientX = IBinder.DeathRecipient {}

        assertNull(registry.register("client-a", recipientA))
        assertNull(registry.register("client-x", recipientX))
        assertEquals(recipientA, registry.register("client-a", supersededA))

        // Consuming one client's registration leaves the other client untouched.
        assertEquals(supersededA, registry.onDeath("client-a"))
        assertEquals(recipientX, registry.onDeath("client-x"))
        assertNull(registry.onDeath("unknown-client"))
    }
}
