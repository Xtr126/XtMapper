package xtr.keymapper.server

/**
 * Single-slot holder for the current client session's service, shared between
 * Handler-thread replacement (install/clear) and binder-thread death claims. Every
 * operation is atomic against the others under one monitor. A death claim records an
 * outstanding shutdown commitment that blocks replacement admission; each later claim
 * (including repeated ones from the same owner after the slot is empty) stacks its own
 * commitment instead of overwriting the first one, so admission stays closed until every
 * requesting shutdown has released. Deferred admission callbacks are delivered only after
 * the final commitment is released, and always outside the monitor.
 */
internal class ClientServiceSlot<K : Any, V : Any> {
    private var entry: Pair<K, V>? = null
    private var pendingReleases: Int = 0
    private val admissionWaiters = mutableListOf<() -> Unit>()

    /**
     * Installs [value] owned by [owner], replacing any previous session. Returns false
     * while a shutdown commitment is outstanding: the session is not admitted and the
     * caller must defer the request.
     */
    fun install(owner: K, value: V): Boolean = synchronized(this) {
        if (pendingReleases != 0) return false
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
     * Validates ownership, clears the slot and records a shutdown commitment in one
     * critical section: when the slot belongs to [owner] (or is empty) it is cleared, one
     * pending release is recorded, and the previous value is returned with
     * superseded=false. When a newer session owns it nothing is cleared and
     * superseded=true tells the caller it must not tear down or terminate anything (it
     * records no commitment, so it has nothing to release either). A slot emptied by an
     * earlier claim keeps stacking commitments for later claims; nothing reopens
     * admission until every one of them is released.
     */
    fun claimForShutdown(owner: K): Pair<V?, Boolean> = synchronized(this) {
        val current = entry
        if (current != null && current.first != owner) return@synchronized null to true
        entry = null
        pendingReleases += 1
        current?.second to false
    }

    /**
     * Registers [onAvailable] when shutdown commitments are active. Returns true when the
     * callback was deferred; returns false when admission is already open and the caller
     * should continue or schedule its retry. The state check and callback registration are
     * atomic with [releaseTerminal], so a release cannot be missed between those steps.
     */
    fun deferUntilAvailable(onAvailable: () -> Unit): Boolean = synchronized(this) {
        if (pendingReleases == 0) {
            false
        } else {
            admissionWaiters.add(onAvailable)
            true
        }
    }

    /**
     * Releases one shutdown commitment. When the final commitment is released, waiting
     * startup callbacks are copied under the monitor and invoked afterward, avoiding
     * callback execution while holding the slot lock.
     */
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
