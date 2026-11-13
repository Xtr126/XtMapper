package xtr.keymapper.server

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OrderedCleanupTest {

    @Test
    fun `runs steps in order`() {
        val seen = mutableListOf<String>()
        OrderedCleanup.run(
            "a" to { seen += "a" },
            "b" to { seen += "b" },
        )
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `an exception in one step does not skip the remaining steps`() {
        val seen = mutableListOf<String>()
        OrderedCleanup.run(
            "a" to { seen += "a" },
            "boom" to { throw IOException("disk full") },
            "c" to { seen += "c" },
        )
        assertEquals(listOf("a", "c"), seen)
    }

    @Test
    fun `a non-Exception throwable is rethrown after every step has run`() {
        val seen = mutableListOf<String>()
        try {
            OrderedCleanup.run(
                "a" to { seen += "a" },
                "fatal" to { throw OutOfMemoryError("oom") },
                "c" to { seen += "c" },
            )
            fail("expected the fatal throwable to be rethrown")
        } catch (e: OutOfMemoryError) {
            assertEquals("oom", e.message)
        }
        assertEquals(listOf("a", "c"), seen)
    }

    @Test
    fun `the first fatal throwable carries later ones suppressed`() {
        try {
            OrderedCleanup.run(
                "a" to { throw OutOfMemoryError("first") },
                "b" to { throw OutOfMemoryError("second") },
            )
            fail("expected a throwable")
        } catch (e: OutOfMemoryError) {
            assertEquals("first", e.message)
            assertEquals(1, e.suppressed.size)
            assertEquals("second", e.suppressed[0].message)
        }
    }

    @Test
    fun `no failure means no throw`() {
        OrderedCleanup.run("a" to { }, "b" to { })
    }
}
