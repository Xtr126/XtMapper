package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume
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

    @Test fun `concurrent transactions keep unicode and stack traces intact (V5)`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        val unicode = listOf("стена „ú“ 🐉", "l1\nl2\nl3", "back\\slash\tend")
        val trace = "java.io.IOException: boom\n\tatFrames:1)\n\tatMain:2) arg=-1"
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit {
                unicode.forEachIndexed { i, m -> store.append(name, listOf(FrameCodec.encode(i.toLong(), "unicode", m))) }
            }
            val b = pool.submit {
                repeat(6) { i -> store.append(name, listOf(FrameCodec.encode(i.toLong(), "exception", trace))) }
            }
            a.get(20, TimeUnit.SECONDS)
            b.get(20, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val frames = store.readFrames(name)
        assertEquals(10, frames.size)
        assertEquals(listOf(0L, 1L, 2L), frames.filter { it.sid == "unicode" }.map { it.seq })
        assertEquals(unicode, frames.filter { it.sid == "unicode" }.map { it.payload })
        assertEquals(List(6) { trace }, frames.filter { it.sid == "exception" }.map { it.payload })
    }

    @Test fun `checksum failing complete line is exposed and blocks the day`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(FrameCodec.encode(1, "s1", "good")))
        val bad = "X1 1 s2 4 deadbeef five"
        val later = FrameCodec.encode(2, "s2", "later")
        RandomAccessFile(File(dir, name), "rw").use {
            it.seek(it.length())
            it.write((bad + "\n" + later.decodeToString()).toByteArray())
        }
        val recovery = store.readRecovery(name)
        assertEquals(2, recovery.frames.size)
        assertTrue(FrameCodec.isDayHeader(recovery.frames[0]))
        assertTrue("corrupt region must be reported: $recovery", recovery.discardedBytes > 0)
        val lengthBefore = File(dir, name).length()
        val error = assertThrows(IOException::class.java) {
            store.append(name, listOf(FrameCodec.encode(3, "s1", "blocked")))
        }
        assertTrue("must be an integrity block, got: ${error.message}", error.message!!.contains("corruption"))
        assertEquals("corrupt bytes are preserved, never truncated", lengthBefore, File(dir, name).length())
    }

    @Test fun `torn tail beyond one window still finds its boundary farther back`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(FrameCodec.encode(1, "s1", "seed")))
        val garbage = ByteArray(80 * 1024) { 'x'.code.toByte() }
        RandomAccessFile(File(dir, name), "rw").use {
            it.seek(it.length())
            it.write(garbage)
        }
        store.append(name, listOf(FrameCodec.encode(2, "s1", "after")))
        val frames = store.readFrames(name)
        assertTrue(frames.any { it.sid == "recovery" })
        assertEquals("after", frames.last().payload)
    }

    @Test fun `torn tail with no boundary inside the search window refuses repair`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(FrameCodec.encode(1, "s1", "seed")))
        val garbage = ByteArray(200 * 1024) { 'x'.code.toByte() }
        RandomAccessFile(File(dir, name), "rw").use {
            it.seek(it.length())
            it.write(garbage)
        }
        val lengthBefore = File(dir, name).length()
        assertThrows(IOException::class.java) {
            store.append(name, listOf(FrameCodec.encode(2, "s1", "after")))
        }
        assertEquals("unrepairable tail must keep its bytes", lengthBefore, File(dir, name).length())
        assertEquals(listOf("seed"), store.readFrames(name).filter { it.sid == "s1" }.map { it.payload })
    }

    @Test fun `append rejects frames that violate the encoding contract`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(FrameCodec.encode(0, "protocol-canonical-seed", "seed")))
        val canonical = FrameCodec.encode(1, "s1", "abc").decodeToString()
        val nonCanonical = canonical.replaceFirst("X1 1", "X1 01")
        val malformed = "X1 1 s|1 3 00000000 abc\n"
        val lengthBefore = File(dir, name).length()
        assertThrows(IllegalArgumentException::class.java) { store.append(name, listOf(nonCanonical.toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) { store.append(name, listOf(malformed.toByteArray())) }
        assertEquals("contract violations never touch the file", lengthBefore, File(dir, name).length())
        store.append(name, listOf(canonical.toByteArray()))
        assertEquals("abc", store.readFrames(name).last().payload)
    }

    @Test fun `parent and two processes interleave frames with exact per-session counts (V5x)`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        LogProbeChild(dir, "coordinator-hook").use { c1 ->
            LogProbeChild(dir, "coordinator-hook").use { c2 ->
                assertEquals("WROTE", c1.command("TRYAPPEND $name"))
                assertEquals("WROTE", c1.command("TRYAPPEND $name"))
                assertEquals("WROTE", c2.command("TRYAPPEND $name"))
                assertEquals("WROTE", c2.command("TRYAPPEND $name"))
                store.append(name, listOf(FrameCodec.encode(0, "parent", "p0")))
                store.append(name, listOf(FrameCodec.encode(1, "parent", "p1")))
            }
        }
        val frames = CoordinatorStore(dir).readFrames(name)
        assertEquals(7, frames.size)
        assertEquals(listOf(0L, 1L), frames.filter { it.sid == "parent" }.map { it.seq })
        val probeSids = frames.map { it.sid }.filter { it.startsWith("probe-") }.distinct()
        assertEquals(2, probeSids.size)
        probeSids.forEach { sid -> assertEquals(listOf(1L, 2L), frames.filter { it.sid == sid }.map { it.seq }) }
    }

    @Test fun `failed data file open releases coordination for the process (V4b)`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(FrameCodec.encode(0, "seed", "seed")))
        Assume.assumeFalse("chmod-based failure injection needs non-root", System.getProperty("user.name") == "root")
        val data = File(dir, name)
        assertTrue(data.setWritable(false))
        try {
            assertThrows(IOException::class.java) { store.append(name, listOf(FrameCodec.encode(1, "s1", "nope"))) }
        } finally {
            assertTrue(data.setWritable(true))
        }
        // A failed open must never wedge coordination: immediately re-entering succeeds.
        store.append(name, listOf(FrameCodec.encode(1, "s1", "recovered")))
        assertEquals("recovered", store.readFrames(name).last().payload)
    }

    @Test fun `duplicate sequential coordinator descriptors leave no exclusion residue (V4b)`() {
        val dir = tmp.newFolder()
        val first = CoordinatorStore(dir)
        val second = CoordinatorStore(dir)
        first.append(name, listOf(FrameCodec.encode(0, "s1", "one")))
        second.append(name, listOf(FrameCodec.encode(1, "s1", "two")))
        second.readRecovery(name)
        first.append(name, listOf(FrameCodec.encode(2, "s1", "three")))
        assertEquals(
            listOf("one", "two", "three"),
            CoordinatorStore(dir).readFrames(name).filter { it.sid == "s1" }.map { it.payload },
        )
    }

    @Test fun `complete line corruption is classified before any truncation`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(FrameCodec.encode(1, "s1", "good")))
        val bad = "X1 1 s2 4 deadbeef five"
        RandomAccessFile(File(dir, name), "rw").use {
            it.seek(it.length())
            it.write((bad + "\n" + "corn tail").toByteArray())
        }
        val before = File(dir, name).readBytes()
        assertThrows(IOException::class.java) {
            store.append(name, listOf(FrameCodec.encode(3, "s1", "blocked")))
        }
        assertArrayEquals("blocked day must be byte-identical, nothing truncated", before, File(dir, name).readBytes())
        val recovery = store.readRecovery(name)
        assertTrue(FrameCodec.isDayHeader(recovery.frames[0]))
        assertEquals(listOf("good"), recovery.frames.filter { it.sid == "s1" }.map { it.payload })
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

    @Test fun `closing the data descriptor inside a held transaction does not release the coordinator lock (V4)`() {
        val dir = tmp.newFolder()
        CoordinatorStore(dir).append(name, listOf(FrameCodec.encode(0, "seed", "seed")))
        LogProbeChild(dir, "coordinator-hook").use { child ->
            assertEquals("ARMED", child.command("ARM"))
            assertEquals("HOOK_REACHED", child.command("TRYAPPEND $name"))
            assertEquals("READCLOSED", child.command("READCLOSE $name"))
            assertThrows(IOException::class.java) {
                CoordinatorStore(dir).append(name, listOf(FrameCodec.encode(1, "s2", "denied")))
            }
            assertThrows(IOException::class.java) { CoordinatorStore(dir).readFrames(name) }
            assertEquals("WROTE", child.command("CONTINUE"))
            CoordinatorStore(dir).append(name, listOf(FrameCodec.encode(1, "s2", "after")))
        }
        val frames = CoordinatorStore(dir).readFrames(name)
        assertEquals(2, frames.count { it.sid != "seed" && it.sid != "protocol" })
    }
}
