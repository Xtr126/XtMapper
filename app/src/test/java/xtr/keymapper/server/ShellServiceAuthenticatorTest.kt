package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellServiceAuthenticatorTest {

    @Test
    fun `only the expected UID and challenge authenticate once`() {
        var acceptedCount = 0
        val authenticator = ShellServiceAuthenticator(
            expectedChallenge = 42L,
            expectedUid = 2_000,
        ) {
            acceptedCount++
        }

        assertFalse(authenticator.authenticate(callingUid = 10_123, challenge = 42L))
        assertFalse(authenticator.authenticate(callingUid = 2_000, challenge = 41L))
        assertFalse(authenticator.isAuthenticated)
        assertEquals(0, acceptedCount)

        assertTrue(authenticator.authenticate(callingUid = 2_000, challenge = 42L))
        assertFalse(authenticator.authenticate(callingUid = 2_000, challenge = 42L))
        assertTrue(authenticator.isAuthenticated)
        assertEquals(1, acceptedCount)
    }
}
