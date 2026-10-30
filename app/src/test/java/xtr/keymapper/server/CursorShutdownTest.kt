package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorShutdownTest {

    @Test
    fun `hide cursor and cleanup both run when hide cursor succeeds`() {
        // Represents the local-overlay path (cursorView != null): hideCursor() hides the
        // overlay without touching the client Binder, so it succeeds even after client death.
        val calls = mutableListOf<String>()
        CursorShutdown.hideThenCleanup(
            hideCursor = { calls.add("hide") },
            cleanup = { calls.add("cleanup") },
        )
        assertEquals(listOf("hide", "cleanup"), calls)
    }

    @Test
    fun `cleanup still runs when hide cursor throws against a dead client binder`() {
        // Represents the no-overlay path (cursorView == null): hideCursor() falls back to
        // mCallback.disablePointer(), which throws (DeadObjectException wrapped in a
        // RuntimeException) when the client is dead. Cleanup must proceed regardless.
        val calls = mutableListOf<String>()
        CursorShutdown.hideThenCleanup(
            hideCursor = {
                calls.add("hide")
                throw RuntimeException("DeadObjectException: client binder died")
            },
            cleanup = { calls.add("cleanup") },
        )
        assertEquals(listOf("hide", "cleanup"), calls)
    }
}
