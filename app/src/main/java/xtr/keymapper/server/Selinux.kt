package xtr.keymapper.server

/**
 * Tracks whether THIS process changed SELinux from a positively known Enforcing start
 * state, and restores Enforcing only when it is positively verified. Command execution
 * and mode verification are injected so JVM tests never run real commands.
 *
 * SELinux state is system-wide: another privileged process can flip it at any moment and
 * its changes cannot be distinguished from ours. That is why we only ever act from a
 * positively known Enforcing start state (so restoring Enforcing cannot overwrite an
 * unrelated pre-existing state) and keep a pending flag on any uncertain outcome instead
 * of guessing.
 */
internal class Selinux(
    private val setenforce: (Int) -> Boolean,
    private val getenforce: () -> String?
) {
    /** True while this instance may have left the device Permissive (restore pending). */
    var restorationPending: Boolean = false
        private set

    /**
     * Puts SELinux permissive. Returns true only when the Permissive state was
     * positively verified after a successful command.
     */
    fun relax(): Boolean {
        // Only a positively known Enforcing start state may be changed; Permissive,
        // unknown or invalid readings fail safely without running setenforce 0
        if (getenforce() != ENFORCING) return false
        if (!setenforce(0)) return false
        return when (getenforce()) {
            PERMISSIVE -> {
                restorationPending = true
                true
            }
            ENFORCING -> false // command exited 0 but no change happened
            // Unknown after a successful command: keep pending so a later restore
            // returns to the known Enforcing start state, but report no verified
            // transition — the command merely launched, nothing is confirmed
            else -> {
                restorationPending = true
                false
            }
        }
    }

    /**
     * Restores enforcing; no-op unless a change is pending. Success requires the state
     * to be positively verified as Enforcing. On any failure the pending flag stays set
     * so a subsequent cleanup attempt can retry.
     */
    fun restore(): Boolean {
        if (!restorationPending) return true
        if (!setenforce(1)) return false
        if (getenforce() != ENFORCING) return false // Permissive or unreadable: unverified
        restorationPending = false
        return true
    }

    companion object {
        private const val PERMISSIVE = "Permissive"
        private const val ENFORCING = "Enforcing"

        /** Real system commands; used by RemoteServiceShell and RemoteService.stopServer. */
        val real = Selinux(
            setenforce = { mode ->
                runCatching {
                    ProcessBuilder("setenforce", mode.toString()).start().waitFor() == 0
                }.getOrDefault(false)
            },
            getenforce = {
                runCatching {
                    val process = ProcessBuilder("getenforce").start()
                    val mode = process.inputStream.bufferedReader().use { it.readLine() }
                    if (process.waitFor() == 0) mode?.trim() else null
                }.getOrNull()
            }
        )
    }
}
