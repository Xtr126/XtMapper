package xtr.keymapper.server

/**
 * Tracks whether THIS process put SELinux into permissive mode and only restores
 * enforcing if it did. Command execution and mode verification are injected so
 * JVM tests never run real setenforce/getenforce commands.
 */
internal class Selinux(
    private val setenforce: (Int) -> Boolean,
    private val getenforce: () -> String?
) {
    var isRelaxed: Boolean = false
        private set

    /** Puts SELinux permissive. Returns true only when this call is what relaxed it. */
    fun relax(): Boolean {
        if (isRelaxed) return true
        if (getenforce() == PERMISSIVE) return false // already permissive before us
        if (!setenforce(0)) return false
        // Unreadable mode after a successful command: trust the exit code.
        // Only a positive "Enforcing" answer contradicts it.
        if (getenforce() == ENFORCING) return false
        isRelaxed = true
        return true
    }

    /**
     * Restores enforcing; no-op unless this instance relaxed SELinux.
     * Keeps isRelaxed=true on failure so a later stop retries.
     */
    fun restore(): Boolean {
        if (!isRelaxed) return true
        if (!setenforce(1)) return false
        if (getenforce() == PERMISSIVE) return false
        isRelaxed = false
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
