package xtr.keymapper.server

import java.util.concurrent.atomic.AtomicBoolean

/** Accepts one response only when both the challenge and Binder caller UID match. */
internal class ShellServiceAuthenticator(
    private val expectedChallenge: Long,
    private val expectedUid: Int,
    private val onAuthenticated: () -> Unit,
) {
    private val accepted = AtomicBoolean(false)

    val isAuthenticated: Boolean
        get() = accepted.get()

    fun authenticate(callingUid: Int, challenge: Long): Boolean {
        if (callingUid != expectedUid || challenge != expectedChallenge) return false
        if (!accepted.compareAndSet(false, true)) return false
        onAuthenticated()
        return true
    }
}
