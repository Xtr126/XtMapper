package xtr.keymapper.server

/**
 * Single-slot holder for the current client session's service, shared between
 * Handler-thread replacement (install/clear) and binder-thread death claims. Every
 * operation is atomic against the others under one monitor, so a death notification's
 * ownership validation and its clearing of the slot happen in the same critical section
 * and can never split around a replacement's install. Generic over the owner key and
 * stored value so JVM tests exercise the arbitration without Android Binder instances.
 */
internal class ClientServiceSlot<K : Any, V : Any> {
    private var entry: Pair<K, V>? = null

    /** Installs [value] owned by [owner], replacing any previous session. */
    fun install(owner: K, value: V) {
        synchronized(this) { entry = owner to value }
    }

    fun get(): V? = synchronized(this) { entry?.second }

    /** Clears the slot unconditionally and returns the value that was stored, if any. */
    fun clear(): V? = synchronized(this) {
        val value = entry?.second
        entry = null
        value
    }

    /**
     * Atomically validates ownership and clears the slot: when the slot currently belongs
     * to [owner] (or is empty) it is cleared and the previous value is returned with
     * superseded=false; when a newer session owns the slot nothing is cleared and
     * superseded=true tells the caller it must not tear down or terminate anything.
     */
    fun claim(owner: K): Pair<V?, Boolean> = synchronized(this) {
        val current = entry ?: return@synchronized null to false
        if (current.first != owner) return@synchronized null to true
        entry = null
        current.second to false
    }

    /** The owner key of the session currently owning the slot, or null when empty. */
    fun currentOwner(): K? = synchronized(this) { entry?.first }
}
