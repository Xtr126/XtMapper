package xtr.keymapper.server

import android.os.Binder
import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClientSessionTest {

    @Test
    fun `sessions are equal only when binder and recipient match`() {
        val binder = Binder()
        val recipient = IBinder.DeathRecipient {}
        val other = IBinder.DeathRecipient {}

        val session = ClientSession(binder, recipient)
        assertEquals(session, ClientSession(binder, recipient))
        assertNotEquals(session, ClientSession(binder, other))
    }

    @Test
    fun `a session reconstructed from the death callback equals the installed one`() {
        // RemoteService rebuilds the session from (binder, recipient) on death;
        // value equality is what lets owner() != session detect a superseded recipient.
        val binder = Binder()
        val recipient = IBinder.DeathRecipient {}
        val installed = ClientSession(binder, recipient)
        val notified = ClientSession(binder, recipient)

        assertEquals(installed, notified)
        assertNotEquals(installed, ClientSession(binder, IBinder.DeathRecipient {}))
    }
}
