package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorShutdownTest {

    @Test
    fun `hide cursor and cleanup both run when hide cursor succeeds`() {
        val calls = mutableListOf<String>()
        CursorShutdown.hideThenCleanup(
            hideCursor = { calls.add("hide") },
            cleanup = { calls.add("cleanup") },
        )
        assertEquals(listOf("hide", "cleanup"), calls)
    }

    @Test
    fun `cleanup still runs when hide cursor throws against a dead client binder`() {
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
