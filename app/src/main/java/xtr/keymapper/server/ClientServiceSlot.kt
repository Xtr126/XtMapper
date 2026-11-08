package xtr.keymapper.server

import android.os.IBinder

internal data class ClientSession(
    val binder: IBinder,
    val recipient: IBinder.DeathRecipient,
)

internal class ClientServiceSlot<K : Any, V : Any> {
    private var entry: Pair<K, V>? = null
    private var pendingReleases: Int = 0
    private val admissionWaiters = mutableListOf<() -> Unit>()

    fun install(owner: K, value: V): Boolean = synchronized(this) {
        if (pendingReleases != 0) return false
        entry = owner to value
        true
    }

    fun get(): V? = synchronized(this) { entry?.second }

    fun owner(): K? = synchronized(this) { entry?.first }

    fun clear(): V? = synchronized(this) {
        val value = entry?.second
        entry = null
        value
    }

    fun claimForShutdown(owner: K): Pair<V?, Boolean> = synchronized(this) {
        val current = entry
        if (current != null && current.first != owner) return@synchronized null to true
        entry = null
        pendingReleases += 1
        current?.second to false
    }

    fun deferUntilAvailable(onAvailable: () -> Unit): Boolean = synchronized(this) {
        if (pendingReleases == 0) {
            false
        } else {
            admissionWaiters.add(onAvailable)
            true
        }
    }

    // Post-admission side effects may run only while this exact session still occupies
    // the slot; shares the claimForShutdown monitor so shutdown cannot overtake the block.
    fun runIfOwner(owner: K, block: () -> Unit): Boolean = synchronized(this) {
        val owned = entry?.first == owner
        if (owned) block()
        owned
    }

    fun releaseTerminal() {
        val callbacks = synchronized(this) {
            if (pendingReleases > 0) pendingReleases -= 1
            if (pendingReleases == 0) {
                admissionWaiters.toList().also { admissionWaiters.clear() }
            } else {
                emptyList()
            }
        }
        callbacks.forEach { it() }
    }
}
