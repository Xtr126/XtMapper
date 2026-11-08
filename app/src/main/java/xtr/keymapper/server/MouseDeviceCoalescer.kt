package xtr.keymapper.server

internal class MouseDeviceCoalescer(
    private val schedule: (() -> Unit) -> Unit,
    private val process: (String) -> Unit,
) {
    private val lock = Any()
    private var pending: String? = null
    private var scheduled = false

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
