package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

// Engine-level acceptance of the wired coordinator session writer (design §2 wiring):
// frames carry the session markers/records, one unified daily file per date, shared-budget
// exhaustion becomes counted drops with the protocol notice, and the date policy and
// integrity outcomes behave per §1/§38.
class CoordinatorSessionWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun makeWriter(
        dir: File,
        clock: MutableClock,
        sessionId: String,
        store: CoordinatorStore = CoordinatorStore(dir),
        onEvent: (String) -> Unit = {},
    ): CoordinatorSessionWriter =
        CoordinatorSessionWriter(store, dir, null, clock, zone, sessionId, onEvent)

    private fun record(clock: MutableClock, message: String = "event"): LogRecord =
        LogRecord(
            time = clock.instant(),
            level = Level.INFO,
            tag = "t",
            message = message,
            throwable = null,
            pid = 1,
            role = "test",
            threadName = "main",
            threadId = 1,
            sessionId = "s",
        )

    private fun framesOf(dir: File, name: String) = CoordinatorStore(dir).readRecovery(name).frames

    @Test
    fun `start, records and end land as frames of one unified daily file in continuing per-session sequence`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val name = "xtmapper-2026-10-01.log"
        makeWriter(dir, clock, "sess-a").use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(appVersion = "t"), clock.instant())
            writer.writeSessionStartMarker()
            assertEquals(BatchWriteOutcome.PROCESSED, writer.write(listOf(record(clock, "first"), record(clock, "second"))))
            writer.endSession(clock.instant(), 600_000_000L, SessionStates.STATUS_CLEAN, 0)
        }
        val frames = framesOf(dir, name)
        assertTrue("the day starts with the P1 header", frames.first().seq == 0L && frames.first().payload == "P1")
        val session = frames.drop(1)
        assertTrue(session.all { it.sid == "sess-a" })
        assertEquals(listOf(1L, 2L, 3L, 4L), session.map { it.seq })
        assertTrue("START marker landed", session.first().payload.lineSequence().any { it == SessionMarkers.START })
        assertTrue("record payloads land verbatim", session.any { it.payload == RecordFormatter.format(record(clock, "first"), zone) })
        val end = session.last()
        assertTrue("END marker landed", end.payload.lineSequence().any { it == SessionMarkers.END })
        assertTrue("END carries the clean status", end.payload.contains("Status: ${SessionStates.STATUS_CLEAN}"))
        assertEquals(
            "one file per day, no role or part files",
            listOf(name),
            dir.listFiles()!!.filter { it.name != ".xtlogger.lock" }.map { it.name },
        )
    }

    @Test
    fun `budget exhaustion becomes counted drops with the protocol notice and a live sink`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val name = "xtmapper-2026-10-01.log"
        val events = ArrayList<String>()
        makeWriter(dir, clock, "sess-b", CoordinatorStore(dir, maxDailyBytes = 2_000L, reserveBytes = 600L)) { events.add(it) }.use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            val outcome = writer.write((1..60).map { record(clock, "pad-$it") })
            assertEquals("the batch still settles; exhaustion is accounted, not a failure", BatchWriteOutcome.PROCESSED, outcome)
            assertFalse("budget exhaustion must never latch the sink down", writer.unavailable)
            assertEquals(60 - (framesOf(dir, name).count { it.sid == "sess-b" } - 1), writer.recordsDroppedByDayLimit.get())
            writer.endSession(clock.instant(), 0L, SessionStates.STATUS_CLEAN, writer.recordsDroppedByDayLimit.get())
        }
        val bytes = File(dir, name).length()
        assertTrue("the shared daily cap bounds the file: $bytes", bytes <= 2_000L)
        val frames = framesOf(dir, name)
        assertTrue(
            "the protocol notice was written",
            frames.any { it.sid == DailyBudget.NOTICE_SID && it.payload == DailyBudget.NOTICE_PAYLOAD },
        )
        val end = frames.last { it.sid == "sess-b" }
        assertTrue("END persisted from the reserve", end.payload.lineSequence().any { it == SessionMarkers.END })
        assertTrue(events.any { it.contains("budget exhausted") })
        assertTrue(events.none { it.contains("sink unavailable") })
    }

    @Test
    fun `day continuation and late routing follow the date policy on the frame engine`() {
        val clock = MutableClock.at("2026-10-03T12:00:00Z")
        val dir = tmp.newFolder()
        val name = "xtmapper-2026-10-03.log"
        makeWriter(dir, clock, "sess-c").use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            writer.write(
                listOf(
                    record(clock, "today"),
                    record(MutableClock.at("2026-10-01T09:00:00Z"), "stale missing day"),
                ),
            )
            writer.endSession(clock.instant(), 0L, SessionStates.STATUS_CLEAN, 0)
        }
        assertEquals(
            "a pruned or never-written old day never resurrects",
            listOf(name),
            dir.listFiles()!!.filter { it.name != ".xtlogger.lock" }.map { it.name },
        )
        val payloads = framesOf(dir, name).map { it.payload }
        val lateIdx = payloads.indexOfFirst { it.lineSequence().any { line -> line == SessionMarkers.LATE } }
        assertTrue("the late marker documents the routing", lateIdx >= 0)
        assertTrue("the late marker names the intended day", payloads[lateIdx].contains("Intended Day: 2026-10-01"))
        val recordIdx = payloads.indexOfFirst { it.contains("message=\"stale missing day\"") }
        assertTrue("the stale record landed explicitly, after its late marker", recordIdx > lateIdx)
    }

    @Test
    fun `delayed record for an existing day file routes by its own date with a continuation marker`() {
        val clock = MutableClock.at("2026-10-03T12:00:00Z")
        val dir = tmp.newFolder()
        CoordinatorStore(dir).append("xtmapper-2026-10-01.log", listOf(FrameCodec.encode(0, "backlog", "kept")))
        makeWriter(dir, clock, "sess-d").use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            writer.write(listOf(record(MutableClock.at("2026-10-01T09:00:00Z"), "stale kept")))
        }
        val old = CoordinatorStore(dir).readRecovery("xtmapper-2026-10-01.log")
        assertTrue("the record lands in its own day", old.frames.any { it.payload.contains("message=\"stale kept\"") })
        assertTrue("the old day opens with a continuation marker", old.frames.any { it.payload.lineSequence().any { line -> line == SessionMarkers.CONTINUED } })
        assertFalse(old.frames.any { it.payload.lineSequence().any { line -> line == SessionMarkers.LATE } })
    }

    @Test
    fun `a restarted writer inherits the day sequence cursor from the surviving frames`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val name = "xtmapper-2026-10-01.log"
        makeWriter(dir, clock, "sess-e").use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            writer.write(listOf(record(clock, "unconfirmed-then-crash")))
            // No endSession, no close bookkeeping beyond the sink teardown: crash surrogate.
        }
        val before = framesOf(dir, name).count { it.sid == "sess-e" }
        makeWriter(dir, clock, "sess-e").use { writer ->
            writer.write(listOf(record(clock, "after restart")))
        }
        val after = framesOf(dir, name).filter { it.sid == "sess-e" }
        assertEquals(before + 1, after.size)
        assertTrue("the restarted writer continues the sequence", after.last().seq == before + 1L)
    }

    @Test
    fun `integrity refusal blocks only the offending day and reports its record drops`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val events = ArrayList<String>()
        val file = File(dir, "xtmapper-2026-10-01.log")
        file.writeText(DayProtocol.header() + "legacy writer after the boundary\n")
        val before = file.readBytes()
        makeWriter(dir, clock, "sess-f") { events.add(it) }.use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            assertFalse("an integrity refusal must not disable other days", writer.unavailable)
            writer.write(listOf(record(clock, "refused")))
            assertEquals(1, writer.recordsDroppedByDayLimit.get())
            assertTrue(before.contentEquals(file.readBytes()))
            clock.advanceSeconds(86_400)
            assertEquals(BatchWriteOutcome.PROCESSED, writer.write(listOf(record(clock, "next day"))))
            assertTrue(framesOf(dir, "xtmapper-2026-10-02.log").any { it.payload.contains("message=\"next day\"") })
            assertEquals(1, events.count { it.contains("DayIntegrityBlockedException") })
        }
    }

    private class TimeoutClock {
        var expired = false
        private var ticks = 0L
        fun now(): Long = if (expired) ++ticks * 10L else 0L
    }

    @Test
    fun `start timeout stays retryable and retries without consuming sequence numbers`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val deadline = TimeoutClock().apply { expired = true }
        val events = ArrayList<String>()
        val coordinator = CoordinatorStore(dir, coordinationDeadlineNanos = 1L, clock = deadline::now)
        makeWriter(dir, clock, "retry-start", coordinator) { events.add(it) }.use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            assertFalse("acquisition timeout is not a permanent sink failure", writer.unavailable)
            deadline.expired = false
            writer.writeSessionStartMarker()
            writer.write(listOf(record(clock, "after timeout")))
            val frames = framesOf(dir, "xtmapper-2026-10-01.log").filter { it.sid == "retry-start" }
            assertEquals(listOf(1L, 2L), frames.map { it.seq })
            assertTrue(frames.first().payload.contains(SessionMarkers.START))
            assertTrue(events.any { it.contains("not attempted") && it.contains("retryable") })
        }
    }

    @Test
    fun `continuation commits only after acquisition succeeds`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val deadline = TimeoutClock()
        val coordinator = CoordinatorStore(dir, coordinationDeadlineNanos = 1L, clock = deadline::now)
        makeWriter(dir, clock, "retry-continued", coordinator).use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            clock.advanceSeconds(86_400)
            deadline.expired = true
            writer.write(listOf(record(clock, "not attempted")))
            assertFalse(writer.unavailable)
            deadline.expired = false
            writer.write(listOf(record(clock, "retry")))
            val frames = framesOf(dir, "xtmapper-2026-10-02.log").filter { it.sid == "retry-continued" }
            assertEquals(listOf(1L, 2L), frames.map { it.seq })
            assertTrue("the retry must still carry CONTINUED", frames.first().payload.contains(SessionMarkers.CONTINUED))
            assertFalse(frames.any { it.payload.contains("message=\"not attempted\"") })
        }
    }

    @Test
    fun `end timeout does not latch the sink or suppress a later end attempt`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val deadline = TimeoutClock()
        val coordinator = CoordinatorStore(dir, coordinationDeadlineNanos = 1L, clock = deadline::now)
        makeWriter(dir, clock, "retry-end", coordinator).use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            deadline.expired = true
            writer.endSession(clock.instant(), 0L, SessionStates.STATUS_CLEAN, 0)
            assertFalse(writer.unavailable)
            deadline.expired = false
            writer.endSession(clock.instant(), 0L, SessionStates.STATUS_CLEAN, 0)
            val frames = framesOf(dir, "xtmapper-2026-10-01.log").filter { it.sid == "retry-end" }
            assertEquals(listOf(1L, 2L), frames.map { it.seq })
            assertEquals(1, frames.count { it.payload.contains(SessionMarkers.END) })
        }
    }

    @Test
    fun `a timeout after an earlier day committed is uncertain rather than definite whole-batch loss`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val deadline = TimeoutClock()
        var arm = false
        val coordinator = CoordinatorStore(dir, coordinationDeadlineNanos = 1L, clock = deadline::now, onCoordinatorLock = {
            if (arm) { arm = false; deadline.expired = true }
        })
        val writer = makeWriter(dir, clock, "mixed-days", coordinator)
        writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
        writer.writeSessionStartMarker()
        val queue = LoggingQueue(4)
        queue.offer(record(clock, "committed first"))
        queue.offer(record(MutableClock.at("2026-10-02T12:00:00Z"), "unattempted second"))
        arm = true
        val worker = AsyncLogWorker(queue, LoggingConfig(), writer, "test")
        worker.start()
        try {
            assertTrue(worker.flush(2_000))
            assertEquals("a known committed member cannot count as definite loss", 0, worker.droppedCount)
            assertEquals("the partially attempted batch retains the conservative contract", 2, worker.uncertainCount)
            assertFalse(writer.unavailable)
            assertTrue(framesOf(dir, "xtmapper-2026-10-01.log").any { it.payload.contains("message=\"committed first\"") })
            deadline.expired = false
            queue.offer(record(clock, "recovered"))
            assertTrue(worker.flush(2_000))
            assertEquals(2, worker.uncertainCount)
            assertEquals(0, queue.pendingWrites.get())
        } finally {
            deadline.expired = false
            worker.close(2_000)
        }
    }

    @Test
    fun `budget rejects logical records without counting start continuation or late markers`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        val coordinator = CoordinatorStore(dir, maxDailyBytes = 1_000L, reserveBytes = 900L)
        makeWriter(dir, clock, "logical-drops", coordinator).use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            assertEquals("START is not a user record", 0, writer.recordsDroppedByDayLimit.get())
            writer.write(listOf(
                record(MutableClock.at("2026-09-30T12:00:00Z"), "late"),
                record(MutableClock.at("2026-10-02T12:00:00Z"), "continued"),
            ))
            assertEquals("only the two submitted records are drops", 2, writer.recordsDroppedByDayLimit.get())
        }
    }

    @Test
    fun `previous disposition matches the latest started session rather than the last global end`() {
        val dir = tmp.newFolder()
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val coordinator = CoordinatorStore(dir)
        val name = "xtmapper-2026-10-01.log"
        fun marker(seq: Long, sid: String, body: String) = FrameCodec.encode(seq, sid, SessionMarkers.block(body))
        coordinator.append(name, listOf(
            marker(1, "older", SessionMarkers.startLine("older", clock.instant(), zone, SessionMetadata(), "first")),
            marker(1, "latest", SessionMarkers.startLine("latest", clock.instant().plusSeconds(1), zone, SessionMetadata(), "open")),
            marker(2, "older", SessionMarkers.endLine("older", clock.instant().plusSeconds(2), zone, 0, SessionStates.STATUS_CLEAN, 0)),
        ))
        assertEquals("${SessionFrames.DISPOSITION_OPEN} (session=latest role=unknown pid=unknown)", SessionFrames.previousDisposition(coordinator, dir))
        coordinator.append(name, listOf(
            marker(2, "latest", SessionMarkers.endLine("latest", clock.instant().plusSeconds(3), zone, 0, SessionStates.STATUS_SERVICE_STOP, 0)),
        ))
        assertEquals("${SessionState.DISPOSITION_UNCLEAN} (session=latest role=unknown pid=unknown)", SessionFrames.previousDisposition(coordinator, dir))
    }

    @Test
    fun `session state reads per-session dispositions from frames`() {
        val clock = MutableClock.at("2026-10-01T12:00:00Z")
        val dir = tmp.newFolder()
        makeWriter(dir, clock, "sess-g").use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
            writer.endSession(clock.instant(), 0L, SessionStates.STATUS_CLEAN, 0)
        }
        assertEquals(
            "the END of the only session closes it",
            "${SessionState.DISPOSITION_CLOSED} (session=sess-g role=unknown pid=unknown)",
            SessionFrames.previousDisposition(CoordinatorStore(dir), dir),
        )
        // An abandoned session (START without END) is reported open, not unclean.
        val dir2 = tmp.newFolder()
        makeWriter(dir2, clock, "sess-h").use { writer ->
            writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writer.writeSessionStartMarker()
        }
        assertEquals(
            "START without END stays open",
            "${SessionFrames.DISPOSITION_OPEN} (session=sess-h role=unknown pid=unknown)",
            SessionFrames.previousDisposition(CoordinatorStore(dir2), dir2),
        )
    }
}
