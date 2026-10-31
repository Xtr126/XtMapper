package xtr.keymapper.server

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/**
 * Bookkeeping for client-binder death registrations, keyed by client. Registering a fresh
 * recipient for a client that already has one returns the superseded registration so the
 * caller unlinks exactly the registration it replaces; service cleanup never consults this
 * registry, so a replacement session's registration survives its predecessor's teardown and
 * stays liable for the client's own later death. Generic over the client key so JVM tests
 * can exercise the same-binder restart decisions without Android Binder instances.
 */
internal class ClientDeathRegistry<T> {
    private val recipientByClient = ConcurrentHashMap<T, IBinder.DeathRecipient>()

    /** Registers [recipient] and returns the registration it supersedes, or null on first start. */
    internal fun register(client: T, recipient: IBinder.DeathRecipient): IBinder.DeathRecipient? {
        return recipientByClient.put(client, recipient)
    }

    /** Consumes the registration of a client that died; later notifications for it see none. */
    internal fun onDeath(client: T): IBinder.DeathRecipient? {
        return recipientByClient.remove(client)
    }
}
