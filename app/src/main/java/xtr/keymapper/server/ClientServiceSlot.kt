package xtr.keymapper.server

/**
 * Single-slot holder for the current client session's service, shared between
 * Handler-thread replacement (install/clear) and binder-thread death claims. Every
 * operation is atomic against the others under one monitor, and a death shutdown
 * commits a terminal state in the same critical section as its ownership decision, so
 * replacement admission during the committed teardown is rejected and none of the
 * shutdown's side effects can reach an installation that arrives later. Generic over
 * the owner key and value so JVM tests exercise the arbitration without Android Binder
 * instances.
 */
internal class ClientServiceSlot<K : Any, V : Any> {
    private var entry: Pair<K, V>? = null
    private var terminalOwner: K? = null

    /**
     * Installs [value] owned by [owner], replacing any previous session. Returns false
     * when a terminal shutdown is committed: the session is not admitted and the caller
     * completes its request without installing it.
     */
    fun install(owner: K, value: V): Boolean = synchronized(this) {
        if (terminalOwner != null) return false
        entry = owner to value
        true
    }

    fun get(): V? = synchronized(this) { entry?.second }

    /** Clears the slot unconditionally and returns the value that was stored, if any. */
    fun clear(): V? = synchronized(this) {
        val value = entry?.second
        entry = null
        value
    }

    /**
     * Validates ownership, clears the slot and commits the terminal shutdown state in
     * one critical section: when the slot belongs to [owner] (or is empty) it is cleared
     * and the previous value is returned with superseded=false; when a newer session
     * owns it nothing is cleared and superseded=true tells the caller it must not tear
     * down or terminate anything. While the terminal state stands, [install] rejects
     * admission until [releaseTerminal] reopens it for the committing caller.
     */
    fun claimForShutdown(owner: K): Pair<V?, Boolean> = synchronized(this) {
        val current = entry
        if (current != null && current.first != owner) return@synchronized null to true
        entry = null
        terminalOwner = owner
        current?.second to false
    }

    /** Releases the terminal shutdown commitment locked by [owner]; a no-op otherwise. */
    fun releaseTerminal(owner: K) {
        synchronized(this) { if (terminalOwner == owner) terminalOwner = null }
    }
}
