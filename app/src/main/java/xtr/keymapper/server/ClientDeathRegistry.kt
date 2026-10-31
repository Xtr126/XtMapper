package xtr.keymapper.server

import android.os.IBinder

/**
 * Bookkeeping for client-binder death registrations.
 *
 * A registration is identified by both the Binder and the exact DeathRecipient. This
 * prevents a delayed notification from a superseded same-Binder restart from consuming
 * the replacement registration. Startup admission can run under the same monitor as death
 * consumption, so a death notification either invalidates the request before admission or
 * runs after the admission decision and performs the normal cleanup.
 */
internal class ClientDeathRegistry<T> {
    private val recipientByClient = HashMap<T, IBinder.DeathRecipient>()

    @Synchronized
    internal fun register(client: T, recipient: IBinder.DeathRecipient): IBinder.DeathRecipient? {
        return recipientByClient.put(client, recipient)
    }

    /**
     * Installs the new registration and links it before allowing a death callback to
     * inspect the registry. If linking fails, restores the previous registration so its
     * already-linked recipient can still deliver cleanup for an existing session.
     */
    @Synchronized
    internal fun registerAndLink(
        client: T,
        recipient: IBinder.DeathRecipient,
        linkToDeath: () -> Unit
    ): IBinder.DeathRecipient? {
        val previous = recipientByClient.put(client, recipient)
        try {
            linkToDeath()
        } catch (failure: Throwable) {
            if (previous == null) {
                recipientByClient.remove(client)
            } else {
                recipientByClient[client] = previous
            }
            throw failure
        }
        return previous
    }

    @Synchronized
    internal fun isCurrent(client: T, recipient: IBinder.DeathRecipient): Boolean {
        return recipientByClient[client] === recipient
    }

    /**
     * Consumes a death notification only when it belongs to the currently registered
     * recipient. A delayed notification from an older same-Binder registration is ignored.
     */
    @Synchronized
    internal fun onDeath(client: T, recipient: IBinder.DeathRecipient): Boolean {
        if (recipientByClient[client] !== recipient) return false
        recipientByClient.remove(client)
        return true
    }

    /**
     * Runs the final startup-admission decision atomically against Binder death handling.
     * If death is observed before this block, [action] is not executed. If death occurs
     * after [isAlive] succeeds, its callback waits for [action] to finish and then performs
     * cleanup for any service admitted by the action.
     */
    @Synchronized
    internal fun runIfCurrent(
        client: T,
        recipient: IBinder.DeathRecipient,
        isAlive: () -> Boolean,
        action: () -> Boolean
    ): Boolean {
        if (recipientByClient[client] !== recipient || !isAlive()) return false
        return action()
    }
}
