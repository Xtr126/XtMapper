package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoordinatorStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "xtmapper-2026-10-01.log"

    @Test fun `first append creates the file with a day header`() {
        val store = CoordinatorStore(tmp.newFolder())
        store.append(name, listOf(FrameCodec.encode(1, "s1", "hello")))
        val frames = store.readFrames(name)
        assertTrue(FrameCodec.isDayHeader(frames[0]))
        assertEquals("hello", frames[1].payload)
    }

    @Test fun `legacy content is refused instead of being reinterpreted`() {
        val dir = tmp.newFolder()
        File(dir, name).writeText("old unframed appends\n")
        assertThrows(IOException::class.java) {
            CoordinatorStore(dir).append(name, listOf(FrameCodec.encode(1, "s1", "x")))
        }
    }

    @Test fun `concurrent writers serialize into intact interleaved frames`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { role ->
                pool.submit(Callable {
                    repeat(25) { i -> store.append(name, listOf(FrameCodec.encode(i.toLong(), "s$role", "r$role$i"))) }
                })
            }
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val frames = store.readFrames(name)
        assertTrue(FrameCodec.isDayHeader(frames.first()))
        assertEquals(51, frames.size)
        for (sid in listOf("s1", "s2")) {
            val seqs = frames.filter { it.sid == sid }.map { it.seq }
            assertEquals((0L..24L).toList(), seqs)
        }
    }

    @Test fun `torn tail is truncated and reported by a recovery frame`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(FrameCodec.encode(1, "s1", "alpha")))
        val torn = "X1 9 s2 4 aaaa"
        RandomAccessFile(File(dir, name), "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(torn.toByteArray())
        }
        store.append(name, listOf(FrameCodec.encode(1, "s1", "beta")))
        val frames = store.readFrames(name)
        assertEquals(
            true,
            frames.any { it.sid == "recovery" && it.payload.contains("discarded=${torn.length}") },
        )
        assertEquals("beta", frames.last().payload)
        assertFalse(File(dir, name).readBytes().decodeToString().contains(torn))
    }

    @Test fun `mid transaction exclusion holds against a second writer process (V3b)`() {
        val dir = tmp.newFolder()
        CoordinatorStore(dir).append(name, listOf(FrameCodec.encode(0, "seed", "seed")))
        LogProbeChild(dir, "coordinator-hook").use { child ->
            assertEquals("ARMED", child.command("ARM"))
            assertEquals("HOOK_REACHED", child.command("TRYAPPEND $name"))
            assertThrows(IOException::class.java) {
                CoordinatorStore(dir).append(name, listOf(FrameCodec.encode(1, "s2", "rejected")))
            }
            assertEquals("WROTE", child.command("CONTINUE"))
            CoordinatorStore(dir).append(name, listOf(FrameCodec.encode(1, "s2", "after")))
        }
        val frames = CoordinatorStore(dir).readFrames(name)
        assertEquals(4, frames.size)
        assertEquals(2, frames.count { it.sid != "seed" && it.sid != "protocol" })
    }
}
