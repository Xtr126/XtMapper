package xtr.keymapper.server

import android.os.IBinder

internal class ClientDeathRegistry<T> {
    private val recipientByClient = HashMap<T, IBinder.DeathRecipient>()

    @Synchronized
    internal fun register(client: T, recipient: IBinder.DeathRecipient): IBinder.DeathRecipient? {
        return recipientByClient.put(client, recipient)
    }

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

    @Synchronized
    internal fun onDeath(client: T, recipient: IBinder.DeathRecipient): Boolean {
        if (recipientByClient[client] !== recipient) return false
        recipientByClient.remove(client)
        return true
    }

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
