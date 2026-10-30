package xtr.keymapper.server

/**
 * Bounds Handler work for evdev mouse-device discovery.
 *
 * A mouse emits REL_X/REL_Y continuously and multiple devices can interleave (A,B,A,B,...),
 * so "post one task per discovery" lets the main-Handler queue grow without bound. This
 * coalescer keeps at most one scheduled drain task and a single latest pending path: submits
 * arriving while a drain is scheduled only replace the pending path, and a submit arriving
 * while the drain is executing is picked up by the drain's loop (never lost). The Handler's
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

    // Runs on the Handler. Drains the latest pending path, looping to pick up any path that
    // arrived mid-execution before releasing the "scheduled" slot.
    private fun drain() {
        while (true) {
            val device = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) scheduled = false
                next
            } ?: return
            process(device)
        }
    }
}
