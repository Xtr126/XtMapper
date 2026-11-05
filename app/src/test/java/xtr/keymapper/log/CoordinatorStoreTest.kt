package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
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

    @Test fun `child processes and the parent append simultaneously with exact per-session records (V5x)`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        LogProbeChild(dir, "coordinator-hook").use { c1 ->
            LogProbeChild(dir, "coordinator-hook").use { c2 ->
                // Park both children BEFORE their append, then release the two children and the
                // parent at one barrier: append attempts really overlap across three writers.
                assertEquals("HELD", c1.command("BARRIERAPPEND $name"))
                assertEquals("HELD", c2.command("BARRIERAPPEND $name"))
                val barrier = CyclicBarrier(3)
                val pool = Executors.newFixedThreadPool(3)
                try {
                    pool.submit(Callable {
                        barrier.await(); store.append(name, listOf(FrameCodec.encode(0, "parent", "p0"))); store.append(name, listOf(FrameCodec.encode(1, "parent", "p1")))
                    })
                    pool.submit(Callable {
                        barrier.await(); assertEquals("WROTE", c1.command("RELEASE"))
                    })
                    pool.submit(Callable {
                        barrier.await(); assertEquals("WROTE", c2.command("RELEASE"))
                    })
                    pool.shutdown()
                    assertTrue("simultaneous phase must settle", pool.awaitTermination(20, TimeUnit.SECONDS))
                } finally {
                    pool.shutdownNow()
                }
                // Post-overlap tail keeps meaningful per-sid sequence checks.
                assertEquals("WROTE", c1.command("TRYAPPEND $name"))
                assertEquals("WROTE", c2.command("TRYAPPEND $name"))
            }
        }
        val frames = CoordinatorStore(dir).readFrames(name)
        assertEquals(7, frames.size)
        assertEquals(listOf(0L, 1L), frames.filter { it.sid == "parent" }.map { it.seq })
        val probeSids = frames.map { it.sid }.filter { it.startsWith("probe-") }.distinct()
        assertEquals(2, probeSids.size)
        probeSids.forEach { sid -> assertEquals(listOf(1L, 2L), frames.filter { it.sid == sid }.map { it.seq }) }
    }

    @Test fun `transaction never starts past the acquisition deadline`() {
        val dir = tmp.newFolder()
        val deadlineNanos = 1_000_000_000L
        // Scripted clock: reads 1–3 (deadline, loop top, post-mutex pre-tryLock) unexpired;
        // from the post-OS-lock check on, expired ⇒ a successful acquisition skips the work.
        var reads = 0
        val store = CoordinatorStore(
            dir, null, deadlineNanos,
            { if (reads++ < 3) 0L else deadlineNanos + 1 },
        )
        val error = assertThrows(IOException::class.java) {
            store.append(name, listOf(FrameCodec.encode(0, "late", "late")))
        }
        assertEquals("log coordination timed out", error.message)
        assertFalse("expired acquisition must not create or touch the day file", File(dir, name).exists())
        assertTrue(store.readFrames(name).isEmpty())
    }

    // On POSIX-record-lock kernels a close frees every lock the process holds on the
    // inode: failed attempts must never be that close. Separate-process proof is on-device.
    @Test fun `failed overlap attempts never release a same-process holder's lock`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir, null, 250_000_000L)
        val holder = RandomAccessFile(File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME), "rw")
        val held = holder.channel.lock()
        try {
            val error = assertThrows(IOException::class.java) {
                store.append(name, listOf(FrameCodec.encode(1, "s1", "blocked")))
            }
            assertEquals("log coordination timed out", error.message)
            assertTrue("holder's exclusion must outlive failed attempts", held.isValid)
            val outsider = RandomAccessFile(File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME), "rw")
            try {
                assertThrows(OverlappingFileLockException::class.java) { outsider.channel.tryLock() }
            } finally {
                outsider.close()
            }
            assertTrue("exclusion intact end-to-end", held.isValid)
        } finally {
            held.release()
            holder.close()
        }
        store.append(name, listOf(FrameCodec.encode(1, "s1", "after")))
        assertEquals("after", CoordinatorStore(dir).readFrames(name).last().payload)
    }

    // Deadline regression reviewer-required: expiry right after local-mutex acquisition
    // (scripted read #3 = the pre-tryLock check) must open no coordinator descriptor for
    // this call — neither the gate file nor the day file is created.
    @Test fun `expiry after local-mutex acquisition creates neither gate nor day file`() {
        val dir = tmp.newFolder()
        val deadlineNanos = 1_000_000_000L
        var reads = 0
        val store = CoordinatorStore(dir, null, deadlineNanos, { if (reads++ < 2) 0L else deadlineNanos + 1 })
        val error = assertThrows(IOException::class.java) {
            store.append(name, listOf(FrameCodec.encode(0, "late", "late")))
        }
        assertEquals("log coordination timed out", error.message)
        assertFalse("no coordinator descriptor may be opened for an expired attempt", File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME).exists())
        assertFalse("day file never created past the deadline", File(dir, name).exists())
    }

    // V7: a crash mid-frame is simulated by writing a PREFIX of the victim frame's bytes
    // (process gone, no completion, no flush) and then reopening the store — the exact
    // recovery policy per prefix length k. Earlier frames always survive, the torn suffix
    // truncates at the last complete frame with ONE recovery frame, and no synthesized
    // END or filler frame may appear.
    @Test fun `V7 kill offset matrix - earlier frames preserved and torn suffix recovered`() {
        val first = FrameCodec.encode(0, "s1", "first")
        val victim = FrameCodec.encode(1, "s1", "café\nline2 ü")
        // Interesting cut points (k bytes of the victim frame kept):
        // 0 = nothing torn; 5 = ASCII region; 15 = mid UTF-8 code point (é split);
        // mergedEscape = right after the payload's escaped-\n backslash; size-1 = everything
        // except the terminator; size = the whole frame including its terminator.
        val mergedEscape = victim.indexOfFirst { it == '\\'.code.toByte() } + 1
        val next = FrameCodec.encode(2, "s1", "after")
        for (k in listOf(0, 5, 15, mergedEscape, victim.size - 1, victim.size)) {
            val dir = tmp.newFolder()
            RandomAccessFile(File(dir, name), "rw").use { file ->
                val head = DayProtocol.header().toByteArray(Charsets.UTF_8)
                file.write(head)
                file.write(first)
                file.write(victim.copyOfRange(0, k))
            }
            CoordinatorStore(dir).append(name, listOf(next))
            val frames = CoordinatorStore(dir).readFrames(name)
            val payloads = frames.map { it.payload }
            // the day header and the earlier frame always survive
            assertTrue(FrameCodec.isDayHeader(frames[0]))
            assertTrue("earlier frame must survive (k=$k)", "first" in payloads)
            // no synthesized END ever appears
            assertTrue("no synthesized END (k=$k)", "END" !in payloads)
            val recoveryCount = frames.count { it.sid == "recovery" }
            if (k == 0) {
                assertEquals(0, recoveryCount)
            } else if (k < victim.size) {
                assertEquals("torn suffix truncates exactly once (k=$k)", 1, recoveryCount)
                assertEquals("discarded=$k", frames.first { it.sid == "recovery" }.payload)
            } else {
                assertEquals(0, recoveryCount)
                assertTrue("complete victim frame survives when its terminator landed (k=$k)", "café\nline2 ü" in payloads)
            }
            assertTrue("post-crash write lands (k=$k)", "after" in payloads)
            // seq gap when the victim was torn: the §2 recoverable-write discrepancy that
            // the writer slice (outcomes batch) reports in diagnostics — never synthesized.
            val s1Seqs = frames.filter { it.sid == "s1" }.map { it.seq }
            when (k) {
                victim.size -> assertEquals(listOf(0L, 1L, 2L), s1Seqs)
                else -> assertEquals(listOf(0L, 2L), s1Seqs)
            }
        }
    }

    @Test fun `cross date continuation keeps framings isolated (V8 store level)`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append("xtmapper-2026-10-01.log", listOf(FrameCodec.encode(0, "s1", "a-start")))
        store.append("xtmapper-2026-10-02.log", listOf(FrameCodec.encode(0, "s1", "b-start")))
        val readOne = store.readRecovery("xtmapper-2026-10-01.log")
        val readTwo = store.readRecovery("xtmapper-2026-10-02.log")
        assertTrue(FrameCodec.isDayHeader(readOne.frames[0]))
        assertTrue(FrameCodec.isDayHeader(readTwo.frames[0]))
        assertEquals(
            listOf("P1", "a-start"),
            readOne.frames.map { it.payload },
        )
        assertEquals(listOf("P1", "b-start"), readTwo.frames.map { it.payload })
        // the continuation records the same session on its own day with its own seq space
        assertEquals(listOf(0L), readOne.frames.filter { it.sid == "s1" }.map { it.seq })
        assertEquals(listOf(0L), readTwo.frames.filter { it.sid == "s1" }.map { it.seq })
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

    // Torn header review: every prefix cut must repair, not reject, and never rescue header-less.
    @Test fun `torn day header prefix is repaired before validation`() {
        val header = DayProtocol.header().toByteArray(Charsets.UTF_8)
        val after = FrameCodec.encode(1, "s1", "after")
        for (k in 1 until header.size) {
            val dir = tmp.newFolder()
            File(dir, name).writeBytes(header.copyOfRange(0, k))
            CoordinatorStore(dir).append(name, listOf(after))
            val frames = CoordinatorStore(dir).readFrames(name)
            assertTrue("header rewritten (k=$k)", FrameCodec.isDayHeader(frames[0]))
            assertTrue("post-crash write lands (k=$k)", "after" in frames.map { it.payload })
            assertTrue(
                "no fabricated recovery frame (k=$k)",
                frames.none { it.sid == "recovery" },
            )
        }
    }

    // Overlap review: a same-JVM channel holding the gate must arrive as a bounded
    // failed attempt, never an escaped OverlappingFileLockException, and must recover.
    @Test fun `same-jvm gate overlap fails bounded and recovers after release`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir, null, 250_000_000L)
        val holder = RandomAccessFile(File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME), "rw")
        val held = holder.channel.lock()
        try {
            val error = assertThrows(IOException::class.java) {
                store.append(name, listOf(FrameCodec.encode(1, "s1", "blocked")))
            }
            assertEquals(
                "overlap must be a failed attempt, not an escaped lock exception",
                "log coordination timed out",
                error.message,
            )
        } finally {
            held.release()
            holder.close()
        }
        store.append(name, listOf(FrameCodec.encode(1, "s1", "after")))
        val frames = CoordinatorStore(dir).readFrames(name)
        assertTrue(FrameCodec.isDayHeader(frames[0]))
        assertEquals("after", frames.last().payload)
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

    // Budget tests use a tiny D/R pair for speed; every assertion derives from file bytes
    // and frame contents, so they hold against any implementation honoring the design
    // budget clause. Pads use fixed 6-digit sequences so every pad is byte-identical and
    // the pre-fill total is exact.
    private val cap = 200_000L
    private val reserve = 60_000L
    private val allowance: Long get() = cap - reserve

    private fun budgetStore(dir: File): CoordinatorStore =
        CoordinatorStore(dir, null, 250_000_000L, { System.nanoTime() }, cap, reserve)

    private fun prefill(dir: File, padCount: Long, extras: List<ByteArray> = emptyList()) {
        val pad = FrameCodec.encode(100_000L, "p", "x")
        assertTrue("test invariant: pad stays 6-digit", padCount + 100_000L < 1_000_000L)
        File(dir, name).outputStream().use { out ->
            out.write(DayProtocol.header().toByteArray(Charsets.UTF_8))
            repeat(padCount.toInt()) { i -> out.write(FrameCodec.encode(100_000L + i, "p", "x")) }
            extras.forEach(out::write)
        }
    }

    private fun padding(): Pair<Long, Long> {
        val pad = FrameCodec.encode(100_000L, "p", "x")
        val n = allowance / pad.size
        return n to (allowance - n * pad.size)
    }

    private fun exhaustionNotice(): ByteArray =
        FrameCodec.encode(0, DailyBudget.NOTICE_SID, DailyBudget.NOTICE_PAYLOAD)

    private fun sessionEndFrame(sequence: Long): ByteArray {
        // Session END marker block framing: SEPARATOR_80 / SESSION END / body / SEPARATOR_80.
        val end = SessionMarkers.block(
            SessionMarkers.endLine("sess", java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"), 0, "clean", 0),
        )
        return FrameCodec.encode(sequence, "lifecycle", end)
    }

    private fun payloadHasMarker(frame: Frame, marker: String) =
        frame.payload.lineSequence().any { SessionMarkers.markerTypeOf(it) == marker }

    @Test fun `exhausted day switches to the reserve and the notice precedes the session end (V10)`() {
        val dir = tmp.newFolder()
        val (padCount, residue) = padding()
        prefill(dir, padCount)
        val store = budgetStore(dir)
        val probe = FrameCodec.encode(100_000L, "probe", "y".repeat(9))
        assertTrue("test invariant: probe exceeds the residue", probe.size > residue)
        store.append(name, listOf(probe))
        var frames = store.readFrames(name)
        assertEquals("probe dropped, notice lands in its place", padCount.toInt() + 2, frames.size)
        val notice = frames.last()
        assertEquals("RESERVE_EXHAUSTED", notice.payload)
        assertEquals(DailyBudget.NOTICE_SID, notice.sid)
        assertTrue("file stays at or below the cap", File(dir, name).length() <= cap)
        store.append(name, listOf(sessionEndFrame(100_000L)))
        frames = store.readFrames(name)
        val noticeIndex = frames.indexOfFirst { it.sid == DailyBudget.NOTICE_SID }
        val endIndex = frames.indexOfFirst { payloadHasMarker(it, SessionMarkers.END) }
        assertTrue("graceful session end survives exhaustion from the reserve", endIndex >= 0)
        assertTrue("notice is reserved first: it precedes the end", noticeIndex in 0 until endIndex)
        assertEquals("the notice stays single", 1, frames.count { it.sid == DailyBudget.NOTICE_SID })
        assertTrue("file stays at or below the cap after the end", File(dir, name).length() <= cap)
        store.append(name, listOf(FrameCodec.encode(1, "probe", "late")))
        frames = store.readFrames(name)
        assertTrue("records never write from the reserve", "late" !in frames.map { it.payload })
        assertEquals("file still ends with the session end", frames[endIndex], frames.last())
    }

    @Test fun `concurrent writers near the cap stay inside the daily budget (V10)`() {
        val dir = tmp.newFolder()
        val (padCount, _) = padding()
        prefill(dir, padCount)
        val store = budgetStore(dir)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { role ->
                pool.submit(Callable {
                    repeat(8) { i ->
                        store.append(name, listOf(FrameCodec.encode(i.toLong(), "t$role", "y".repeat(500))))
                    }
                })
            }
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val frames = store.readFrames(name)
        assertTrue("file never exceeds the daily cap", File(dir, name).length() <= cap)
        assertEquals("exactly one exhaustion notice under shared accounting", 1, frames.count { it.sid == DailyBudget.NOTICE_SID })
        for (role in listOf("t1", "t2")) {
            val seqs = frames.filter { it.sid == role }.map { it.seq }
            assertTrue("written $role sequences keep arrival order", seqs == seqs.sorted())
        }
    }

    @Test fun `a fresh store continues the day's budget instead of re-issuing it (V10)`() {
        val dir = tmp.newFolder()
        val (padCount, _) = padding()
        prefill(dir, padCount)
        val first = budgetStore(dir)
        first.append(name, listOf(FrameCodec.encode(100_000L, "probe", "y".repeat(9))))
        val afterFirst = File(dir, name).length()
        val firstFrames = first.readFrames(name).size
        val second = budgetStore(dir)
        second.append(name, listOf(FrameCodec.encode(100_000L, "probe", "y".repeat(9))))
        assertEquals("restart inherits the spent allowance: no fresh write", firstFrames, second.readFrames(name).size)
        assertEquals(afterFirst, File(dir, name).length())
        second.append(name, listOf(sessionEndFrame(100_000L)))
        val frames = second.readFrames(name)
        assertTrue("reserve still grants a graceful end after restart", payloadHasMarker(frames.last(), SessionMarkers.END))
        assertEquals(1, frames.count { it.sid == DailyBudget.NOTICE_SID })
    }

    @Test fun `session end that cannot fit the remaining reserve stays unpersisted (V11)`() {
        val dir = tmp.newFolder()
        val (padCount, _) = padding()
        // Pre-spend the reserve with notice-classified frames so its remainder cannot hold
        // a session end.
        val notice = exhaustionNotice()
        val noteCount = reserve / notice.size
        prefill(
            dir, padCount,
            List(noteCount.toInt()) { seq -> FrameCodec.encode(100_000L + seq, DailyBudget.NOTICE_SID, DailyBudget.NOTICE_PAYLOAD) },
        )
        val before = File(dir, name).readBytes()
        budgetStore(dir).append(name, listOf(sessionEndFrame(100_000L)))
        assertArrayEquals("nothing lands when the reserve cannot hold the end", before, File(dir, name).readBytes())
        val frames = CoordinatorStore(dir).readFrames(name)
        assertTrue(
            "the end never reaches the day",
            frames.none { payloadHasMarker(it, SessionMarkers.END) },
        )
    }

    @Test fun `a record exactly filling the normal allowance lands and the next one drops with the notice (V11)`() {
        val dir = tmp.newFolder()
        val (padCount, residue) = padding()
        // A filler record sized to residue + 2*padSize leaves the normal allowance EXACTLY
        // 0; payload length tunes the size continuously inside the 2-digit len block.
        val fillerTarget = residue + 2 * FrameCodec.encode(100_000L, "p", "x").size
        val filler = FrameCodec.encode(100_000L, "filler", "z".repeat(residue.toInt() + 20))
        assertEquals("test invariant: filler hits the exact boundary", fillerTarget, filler.size.toLong())
        prefill(dir, padCount - 2, listOf(filler))
        val headerSize = DayProtocol.header().toByteArray(Charsets.UTF_8).size.toLong()
        assertEquals(headerSize + allowance, File(dir, name).length())
        val store = budgetStore(dir)
        store.append(name, listOf(FrameCodec.encode(100_000L, "probe", "tiny")))
        assertEquals(
            "exact fill holds the boundary: only the notice is added",
            headerSize + allowance + exhaustionNotice().size.toLong(),
            File(dir, name).length(),
        )
        val frames = store.readFrames(name)
        assertEquals("the boundary record survives", 1, frames.count { it.sid == "filler" })
        assertTrue("the next record was dropped", "tiny" !in frames.map { it.payload })
        assertEquals("notice written in the same transaction, last", "RESERVE_EXHAUSTED", frames.last().payload)
    }

    @Test fun `no same-day rollover or extra day files after exhaustion (V10)`() {
        val dir = tmp.newFolder()
        val (padCount, _) = padding()
        prefill(dir, padCount)
        val store = budgetStore(dir)
        store.append(name, listOf(FrameCodec.encode(100_000L, "probe", "y".repeat(9))))
        val framesBefore = store.readFrames(name).size
        store.append(name, listOf(FrameCodec.encode(1, "probe", "more")))
        store.append(name, listOf(FrameCodec.encode(2, "other", "more")))
        assertEquals(
            "the exhausted day keeps one log file",
            setOf(name, FileSystemLogStore.LIFECYCLE_LOCK_NAME),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
        assertEquals("post-exhaustion appends change nothing", framesBefore, store.readFrames(name).size)
        assertEquals(1, store.readFrames(name).count { it.sid == DailyBudget.NOTICE_SID })
    }
}
