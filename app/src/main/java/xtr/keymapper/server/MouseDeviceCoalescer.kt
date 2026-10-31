package xtr.keymapper.server

/**
 * Bounds Handler work for evdev mouse-device discovery.
 *
 * A mouse emits REL_X/REL_Y continuously and multiple devices can interleave (A,B,A,B,...),
 * so "post one task per discovery" lets the main-Handler queue grow without bound. This
 * coalescer keeps at most one scheduled drain task and a single latest pending path: submits
 * arriving while a drain is scheduled only replace the pending path, and a submit arriving
 * while the drain is executing is picked up by one continuation posted at the queue tail
 * (never lost). Each invocation processes a single selection so queued Handler work such as
 * shutdown cannot be starved by a continuous discovery stream. The Handler's
 * "differs from the active device" check stays in [process] as the source of truth.
 */
internal class MouseDeviceCoalescer(
    private val schedule: (() -> Unit) -> Unit,
    private val process: (String) -> Unit,
) {
    private val lock = Any()
    private var pending: String? = null
    private var scheduled = false

    /**
     * Records [device] as the latest wanted path, scheduling at most one drain task.
     * Safe to call from the getevent reader thread.
     */
    fun request(device: String) {
        val shouldSchedule: Boolean
        synchronized(lock) {
            pending = device
            shouldSchedule = !scheduled
            scheduled = true
        }
        // Invoke the scheduler outside the lock so an injected callback cannot deadlock.
        if (shouldSchedule) schedule(::drain)
    }

    // Runs on the Handler. Processes at most one selection per invocation so queued Handler
    // work (stopServer, Binder-death cleanup) cannot be starved by a continuous discovery
    // stream: a still-pending selection is handed to one continuation posted at the queue
    // tail, keeping at most one coalescer task queued at any time.
    private fun drain() {
        val device = synchronized(lock) {
            val next = pending
            pending = null
            next
        }
        if (device != null) process(device)
        val morePending = synchronized(lock) {
            if (pending == null) {
                scheduled = false
                false
            } else {
                true
            }
        }
        if (morePending) schedule(::drain)
    }
}
