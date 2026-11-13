package xtr.keymapper.log

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionFramesRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneOffset.UTC
    private val name = "xtmapper-2026-10-01.log"
    private val start = Instant.parse("2026-10-01T12:00:00Z")

    private fun marker(seq: Long, sid: String, body: String) =
        FrameCodec.encode(seq, sid, SessionMarkers.block(body))

    private fun started(sid: String, at: Instant = start, role: String = "shell") =
        marker(1, sid, SessionMarkers.startLine(sid, at, zone, SessionMetadata(), "first") + "\nRole: $role\nPID: 42")

    private fun ended(sid: String, status: String = SessionStates.STATUS_CLEAN) =
        marker(2, sid, SessionMarkers.endLine(sid, start.plusSeconds(10), zone, 0, status, 0))

    @Test fun `pid reuse never lets an older session end close the latest session`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(started("old"), started("new", start.plusSeconds(1), "app"), ended("old")))
        assertEquals("No END observed (session=new role=app pid=42)", SessionFrames.previousDisposition(store, dir))
        store.append(name, listOf(ended("new", SessionStates.STATUS_SERVICE_STOP)))
        assertEquals("Did not close cleanly (session=new role=app pid=42)", SessionFrames.previousDisposition(store, dir))
    }

    @Test fun `latest session uses its start instant rather than filename order across zones`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append("xtmapper-2026-10-02.log", listOf(started("earlier", start.plusSeconds(86_400)), ended("earlier")))
        store.append(name, listOf(started("later", start.plusSeconds(86_401))))
        assertEquals("No END observed (session=later role=shell pid=42)", SessionFrames.previousDisposition(store, dir))
    }

    @Test fun `a newer continuation whose start was pruned cannot report an older closed session`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(started("old"), ended("old")))
        val continuation = SessionMarkers.continuedLine("pruned", start.plusSeconds(1), start.plusSeconds(86_400), zone)
        store.append("xtmapper-2026-10-02.log", listOf(marker(1, "pruned", continuation)))
        assertTrue(SessionFrames.previousDisposition(store, dir).startsWith("Unknown (history pruned or inspection incomplete)"))
    }

    @Test fun `cross-date continuation retains the session identity and matches its own end`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(started("cross-date")))
        store.append("xtmapper-2026-10-02.log", listOf(
            marker(1, "cross-date", SessionMarkers.continuedLine("cross-date", start, start.plusSeconds(86_400), zone)),
            ended("cross-date"),
        ))
        assertEquals("Closed cleanly (session=cross-date role=shell pid=42)", SessionFrames.previousDisposition(store, dir))
    }

    @Test fun `corrupt recent history never reports a confident closed disposition`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(started("closed"), ended("closed")))
        File(dir, "xtmapper-2026-10-02.log").writeText(DayProtocol.header() + "checksum-failing line\n")
        assertTrue(SessionFrames.previousDisposition(store, dir).startsWith("Unknown (history pruned or inspection incomplete)"))
    }

    @Test fun `inspection timeout reports unknown rather than a confident fallback`() {
        val dir = tmp.newFolder()
        CoordinatorStore(dir).append(name, listOf(started("closed"), ended("closed")))
        var tick = 0L
        val timedOut = CoordinatorStore(dir, coordinationDeadlineNanos = 1L, clock = { ++tick * 10L })
        assertEquals("Unknown (history pruned or inspection incomplete)", SessionFrames.previousDisposition(timedOut, dir))
    }

    @Test fun `pipeline start and next previous-session line contain actual role and pid`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        val factory = TestPipelineFactory(FileSystemLogStore(dir), MutableClock.at("2026-10-01T12:00:00Z"), coordinator = store)
        val first = factory.start(Role.SHELL)
        assertTrue(first.close())
        val next = factory.start(Role.APP)
        assertTrue(next.close())
        val starts = store.readFrames(name).filter { it.payload.lineSequence().any { line -> line == SessionMarkers.START } }
        assertEquals(2, starts.size)
        assertTrue(starts[0].payload.contains("Role: shell\nPID: 42"))
        assertTrue(starts[1].payload.contains("Role: app\nPID: 42"))
        assertTrue(starts[1].payload.contains("Previous Session: Closed cleanly (session=${first.sessionId} role=shell pid=42)"))
    }

    @Test fun `writer reports a missing committed sequence without reusing or replaying it`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val events = ArrayList<String>()
        CoordinatorSessionWriter(store, dir, null, clock, clock.zone, "missing-seq", events::add).use { writer ->
            writer.configure("first", SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            fun record(message: String) = LogRecord.now(clock.instant(), Level.INFO, "t", message, role = "app")
            writer.write(listOf(record("committed")))
            val file = File(dir, name)
            val surviving = store.readFrames(name).filter { it.sid != "missing-seq" || it.seq != 2L }
            file.writeBytes(surviving.flatMap { FrameCodec.encode(it.seq, it.sid, it.payload).asIterable() }.toByteArray())
            writer.write(listOf(record("after loss")))
            writer.write(listOf(record("next")))
            assertEquals(listOf(1L, 3L, 4L), store.readFrames(name).filter { it.sid == "missing-seq" }.map { it.seq })
            assertTrue(events.any { it.contains("sequence discrepancy") && it.contains("missing-seq") && it.contains("2") })
            assertTrue(SessionFrames.previousDisposition(store, dir).startsWith("Unknown (history pruned or inspection incomplete)"))
        }
    }

    @Test fun `orphan end cannot hide behind an older complete session`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        val orphan = marker(1, "orphan", SessionMarkers.endLine("orphan", start.plusSeconds(20), zone, 0, SessionStates.STATUS_CLEAN, 0))
        store.append(name, listOf(started("closed"), ended("closed"), orphan))
        assertTrue(SessionFrames.previousDisposition(store, dir).startsWith(SessionFrames.DISPOSITION_UNKNOWN))
    }

    @Test fun `nonselected orphan continuation still makes history incomplete`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        val orphan = marker(1, "orphan", SessionMarkers.continuedLine("orphan", start.minusSeconds(20), start, zone))
        store.append(name, listOf(orphan, started("closed"), ended("closed")))
        assertTrue(SessionFrames.previousDisposition(store, dir).startsWith(SessionFrames.DISPOSITION_UNKNOWN))
    }

    @Test fun `empty newer day prevents a confident previous disposition`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        store.append(name, listOf(started("closed"), ended("closed")))
        File(dir, "xtmapper-2026-10-02.log").writeBytes(byteArrayOf())
        assertTrue(SessionFrames.previousDisposition(store, dir).startsWith(SessionFrames.DISPOSITION_UNKNOWN))
    }

    @Test fun `headerless contiguous frames cannot establish a clean session`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        File(dir, name).writeBytes(started("closed") + ended("closed"))
        assertTrue(SessionFrames.previousDisposition(store, dir).startsWith(SessionFrames.DISPOSITION_UNKNOWN))
    }

    @Test fun `encoding failure still reports the detected cursor loss after gate release`() {
        val dir = tmp.newFolder()
        val store = CoordinatorStore(dir)
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        var discrepancyReported = false
        var inspectedAfterRelease = false
        CoordinatorSessionWriter(store, dir, null, clock, clock.zone, "lost") { event ->
            if (event.contains("sequence discrepancy")) {
                discrepancyReported = true
                inspectedAfterRelease = store.readFrames(name).any { it.sid == "lost" }
            }
        }.use { writer ->
            writer.configure("first", SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            writer.write(listOf(LogRecord.now(clock.instant(), Level.INFO, "t", "committed", role = "app")))
            val file = File(dir, name)
            val surviving = store.readFrames(name).filter { it.sid != "lost" || it.seq != 2L }
            file.writeBytes(surviving.flatMap { FrameCodec.encode(it.seq, it.sid, it.payload).asIterable() }.toByteArray())
            val before = file.readBytes()
            writer.endSession(clock.instant(), 0, "x".repeat(FrameCodec.MAX_PAYLOAD_BYTES), 0)
            assertTrue(writer.unavailable)
            assertArrayEquals(before, file.readBytes())
            assertTrue("the already-observed missing cursor is reported", discrepancyReported)
            assertTrue("the report runs after coordinator release", inspectedAfterRelease)
        }
    }
}
