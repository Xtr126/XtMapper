package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class SessionLogWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Berlin")

    private fun newStore(): FileSystemLogStore = FileSystemLogStore(tmp.newFolder())

    private fun makeWriter(
        store: LogStore,
        clock: MutableClock,
        sessionId: String,
        role: String? = null,
        config: LoggingConfig = LoggingConfig(queueCapacity = 8, maxFileBytes = 4L * 1024 * 1024),
        onEvent: (String) -> Unit = {},
    ): SessionLogWriter {
        val writer = SessionLogWriter(
            store = store,
            role = role,
            config = config,
            clock = clock,
            zone = zone,
            sessionId = sessionId,
            onEvent = onEvent,
        )
        // Caller arrangement from the pipeline: configure before the start marker.
        writer.configure(
            previousDisposition = SessionState.DISPOSITION_FIRST,
            metadata = SessionMetadata(appVersion = "t", buildType = "Debug"),
            sessionStart = clock.instant(),
        )
        return writer
    }

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

    @Test
    fun `start marker is written into the day file with metadata and previous disposition`() {
        val clock = MutableClock.at("2026-10-22T09:15:32.400Z")
        val store = newStore()
        val writer = makeWriter(store, clock, "sess-001")
        writer.writeSessionStartMarker()

        val name = "xtmapper-2026-10-22.log"
        val text = LogFiles.readAll(store, name)
        assertTrue(text.contains(RecordFormatter.SEPARATOR_80))
        assertTrue(text.contains("SESSION START"))
        assertTrue(text.contains("Session ID: sess-001"))
        assertTrue(text.contains("Start Time: 2026-10-22T11:15:32.400+02:00")) // Europe/Berlin is +02:00 in October
        assertTrue(text.contains("App Version: t"))
        assertTrue(text.contains("Previous Session: First session"))

        // Nothing is overwritten when the marker block is followed by events.
        writer.write(listOf(record(clock)))
        val text2 = LogFiles.readAll(store, name)
        assertTrue(text2.contains("SESSION START"))
        assertTrue(text2.contains("message=\"event\""))
        writer.close()
    }

    @Test
    fun `two sessions append into the same daily file and both stay complete`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()

        val first = makeWriter(store, clock, "sess-first")
        first.writeSessionStartMarker()
        first.write(listOf(record(clock, "first event")))
        first.endSession(clock.instant(), 1_000_000_000L, "Clean shutdown", 0)
        first.close()

        clock.advanceSeconds(60)
        val second = makeWriter(store, clock, "sess-second")
        second.writeSessionStartMarker()
        second.write(listOf(record(clock, "second event")))
        second.close()

        val name = "xtmapper-2026-10-22.log"
        val text = LogFiles.readAll(store, name)
        assertTrue(text.contains("Session ID: sess-first"))
        assertTrue(text.contains("Session ID: sess-second"))
        assertTrue(text.contains("message=\"first event\""))
        assertTrue(text.contains("message=\"second event\""))
        assertTrue(text.contains("Status: Clean shutdown"))
        // Correct chronological placement: the second session's marker appears
        // after the first session's END marker.
        assertTrue(text.indexOf("Session ID: sess-first") < text.indexOf("Status: Clean shutdown"))
        assertTrue(text.indexOf("Status: Clean shutdown") < text.indexOf("Session ID: sess-second"))
    }

    @Test
    fun `midnight rollover keeps the session id and writes one continuation marker`() {
        // Berlin is UTC+02:00 in late October 2026, so the session starts at
        // 21:58Z (23:58 local) on the 22nd and rolls over before midnight local.
        val clock = MutableClock.at("2026-10-22T21:58:00Z")
        val store = newStore()
        val writer = makeWriter(store, clock, "sess-rollover")
        writer.writeSessionStartMarker()
        writer.write(listOf(record(clock, "before midnight")))   // xtmapper-2026-10-22.log

        clock.advanceSeconds(60 * 60 + 1) // 3601s → 22:58:01Z = 00:58:01 local next day → new daily file
        writer.write(listOf(record(clock, "after midnight")))
        clock.advanceSeconds(120)
        writer.write(listOf(record(clock, "still same session")))

        val day1 = LogFiles.readAll(store, "xtmapper-2026-10-22.log")
        val day2 = LogFiles.readAll(store, "xtmapper-2026-10-23.log")
        assertTrue(day1.contains("SESSION START"))
        assertTrue(day1.contains("Session ID: sess-rollover"))
        assertTrue(day1.contains("message=\"before midnight\""))
        assertFalse(day1.contains("after midnight"))

        assertTrue(day2.contains(RecordFormatter.SEPARATOR_80))
        assertTrue(day2.contains("SESSION CONTINUED"))
        assertTrue(day2.contains("Session ID: sess-rollover"))
        assertTrue(day2.contains("Continuation Time: 2026-10-23T00:58:01.000+02:00"))
        assertTrue(day2.contains("Original Start Time: 2026-10-22T23:58:00.000+02:00"))
        assertTrue(day2.contains("message=\"after midnight\""))
        assertTrue(day2.contains("message=\"still same session\""))
        // Exactly one continuation block per rollover day.
        assertEquals(1, Regex("SESSION CONTINUED").findAll(day2).count())
        // The rollover is not a restart: no SESSION START appears in the new day.
        assertFalse(day2.contains("SESSION START"))
        assertFalse(day2.contains("SESSION END"))
        writer.close()
    }

    @Test
    fun `rotation names keep the log extension and never reuse indexes`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()
        val smallCap: LoggingConfig = LoggingConfig(
            queueCapacity = 8,
            maxFileBytes = 220, // the single start marker block exceeds this
            maxPartsPerDay = 8,
        )
        val writer = makeWriter(store, clock, "sess-rot", config = smallCap)
        writer.writeSessionStartMarker()
        repeat(6) { writer.write(listOf(record(clock, "event $it"))) }

        val names = store.listFiles().map { it.name }.sorted()
        assertTrue("base daily file exists", names.contains("xtmapper-2026-10-22.log"))
        assertTrue("rotated parts exist", names.any { it.endsWith("-part002.log") })
        names.forEach { assertTrue("only .log files: $it", it.endsWith(".log")) }
        assertTrue("no .log.N ever produced", names.none { Regex("log\\.\\d+$").containsMatchIn(it) })
        writer.close()
    }

    @Test
    fun `part limit stops unlimited growth and drops are accounted`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()
        val events = mutableListOf<String>()
        val config = LoggingConfig(
            queueCapacity = 8,
            maxFileBytes = 100,
            maxPartsPerDay = 2, // base + one part
        )
        val writer = makeWriter(store, clock, "sess-limit", config = config, onEvent = { events.add(it) })
        writer.writeSessionStartMarker()
        repeat(12) { writer.write(listOf(record(clock, "event $it"))) }

        writer.write(listOf(record(clock, "dropped-on-limit")))
        assertTrue("part limit reported once",
            events.count { it.contains("part limit") } == 1)
        assertTrue("dropped counter > 0", writer.recordsDroppedByDayLimit.get() > 0)
        val names = store.listFiles()
        assertTrue("at most base + 2 parts", names.size <= 3)
        writer.close()
    }

    @Test
    fun `exhausted day stops relisting and reopening the log sink`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val backing = newStore()
        val listCalls = java.util.concurrent.atomic.AtomicInteger()
        val store = object : LogStore {
            override fun listFiles(): List<LogFileInfo> {
                listCalls.incrementAndGet()
                return backing.listFiles()
            }
            override fun openAppendWriter(name: String): LogFileWriter = backing.openAppendWriter(name)
            override fun readTail(name: String, maxLines: Int): List<String> = backing.readTail(name, maxLines)
            override fun delete(name: String): Boolean = backing.delete(name)
        }
        val config = LoggingConfig(queueCapacity = 8, maxFileBytes = 100, maxPartsPerDay = 2)
        val writer = makeWriter(store, clock, "sess-limit-cache", config = config)
        writer.writeSessionStartMarker()
        repeat(12) { writer.write(listOf(record(clock, "initial-$it"))) }
        assertTrue("day part budget exhausted", writer.recordsDroppedByDayLimit.get() > 0)

        val callsAfterExhaustion = listCalls.get()
        repeat(10) { writer.write(listOf(record(clock, "drop-$it"))) }
        assertEquals("dropped records must not repeat directory I/O", callsAfterExhaustion, listCalls.get())
        writer.close()
    }

    @Test
    fun `start marker at exhausted daily limit does not disable next day logging`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()
        val events = mutableListOf<String>()
        val config = LoggingConfig(
            queueCapacity = 8,
            maxFileBytes = 100,
            maxPartsPerDay = 2,
        )

        val first = makeWriter(store, clock, "sess-fill-limit", config = config, onEvent = { events.add(it) })
        first.writeSessionStartMarker()
        repeat(30) { first.write(listOf(record(clock, "fill-$it"))) }
        assertTrue("first session exhausts the daily part budget", first.recordsDroppedByDayLimit.get() > 0)
        first.close()

        val second = makeWriter(store, clock, "sess-start-at-limit", config = config, onEvent = { events.add(it) })
        second.writeSessionStartMarker()
        assertFalse("a full daily budget is not a sink failure", second.unavailable)

        val finalPartText = LogFiles.readAll(store, "xtmapper-2026-10-22-part002.log")
        assertTrue("START marker is retained in the final part", finalPartText.contains("Session ID: sess-start-at-limit"))

        clock.advanceSeconds(24 * 60 * 60)
        second.write(listOf(record(clock, "after midnight")))
        assertFalse("logging resumes on the next day", second.unavailable)
        assertTrue(
            LogFiles.readAll(store, "xtmapper-2026-10-23.log").contains("message=\"after midnight\"")
        )
        assertFalse("the part limit must not be reported as a sink failure", events.any { it.contains("file sink unavailable") })

        second.endSession(clock.instant(), 1_000_000_000L, "Clean shutdown", 0)
        second.close()
    }

    @Test
    fun `end session writes duration, status and drops, and is idempotent`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()
        val writer = makeWriter(store, clock, "sess-end")
        writer.writeSessionStartMarker()
        writer.write(listOf(record(clock)))
        writer.endSession(clock.instant(), 1_500_000_000L, "Clean shutdown", 2)
        writer.endSession(clock.instant(), 9_000_000_000L, "ignored second call", 0)
        writer.close()

        val text = LogFiles.readAll(store, "xtmapper-2026-10-22.log")
        assertTrue(text.contains("SESSION END"))
        assertTrue(text.contains("Duration: 00:00:01.500"))
        assertTrue(text.contains("Status: Clean shutdown"))
        assertTrue(text.contains("Dropped: 2"))
        assertEquals(1, Regex("SESSION END").findAll(text).count())
    }

    @Test
    fun `storage failure disables the sink once and keeps reporting bounded`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val failingStore = object : LogStore {
            override fun listFiles() = emptyList<LogFileInfo>()
            override fun openAppendWriter(name: String): LogFileWriter =
                throw java.io.IOException("readonly")
            override fun readTail(name: String, maxLines: Int) = emptyList<String>()
            override fun delete(name: String) = false
        }
        val events = mutableListOf<String>()
        val writer = makeWriter(failingStore, clock, "sess-fail", onEvent = { events.add(it) })
        writer.writeSessionStartMarker()
        assertTrue(writer.unavailable)
        writer.write(listOf(record(clock), record(clock, "second")))
        writer.endSession(clock.instant(), 0, "Clean shutdown", 0) // must not throw

        assertEquals("exactly one failure report", 1, events.count { it.contains("file sink unavailable") })
        writer.close()
    }

    @Test
    fun `session end after the part limit writes END into the final part and stays available`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()
        val events = mutableListOf<String>()
        val config = LoggingConfig(
            queueCapacity = 8,
            maxFileBytes = 100,
            maxPartsPerDay = 2, // base + one part
        )
        val writer = makeWriter(store, clock, "sess-limit-end", config = config, onEvent = { events.add(it) })
        writer.writeSessionStartMarker()
        repeat(12) { writer.write(listOf(record(clock, "event $it"))) }
        // The budget is exhausted; further records are dropped and counted.
        writer.write(listOf(record(clock, "past-limit")))
        assertTrue("records dropped by day limit", writer.recordsDroppedByDayLimit.get() > 0)

        // Closing the session must neither throw nor claim a sink failure, and
        // the END marker lands in the day's final part without a ninth part.
        writer.endSession(clock.instant(), 1_500_000_000L, "Service stopped", 0)
        writer.endSession(clock.instant(), 9_000_000_000L, "ignored second call", 0) // idempotent
        assertFalse("healthy sink must not be flagged unavailable", writer.unavailable)
        assertEquals("no spurious sink-failure event", 0, events.count { it.contains("file sink unavailable") })

        val finalPartText = LogFiles.readAll(store, "xtmapper-2026-10-22-part002.log")
        assertTrue(finalPartText.contains("SESSION END"))
        assertTrue(finalPartText.contains("Status: Service stopped"))
        assertEquals(1, Regex("SESSION END").findAll(finalPartText).count())
        assertEquals("still exactly base + one part", 2, store.listFiles().size)
        writer.close()
    }

    @Test
    fun `a contended file sink fails once and gracefully without touching the held file`() {
        // Production consumption of the refusal protocol: when the session's
        // daily file already has an active handler (same or other process),
        // the session writer goes down cleanly (one bounded report, no lines
        // written into a file it does not protect) instead of appending
        // un-locked into it.
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()

        val foreign = store.openAppendWriter("xtmapper-2026-10-22.log")
        try {
            val events = mutableListOf<String>()
            val writer = makeWriter(store, clock, "sess-contended", onEvent = { events.add(it) })
            writer.writeSessionStartMarker()
            writer.write(listOf(record(clock), record(clock, "second")))
            writer.endSession(clock.instant(), 0, "Clean shutdown", 0) // must not throw

            assertTrue("contended open must take the sink down", writer.unavailable)
            assertEquals("exactly one failure report", 1, events.count { it.contains("file sink unavailable") })
            assertTrue("report names the refused file", events.any { it.contains("already open") })
            // Nothing was appended to the foreign holder's file.
            assertEquals("", LogFiles.readAll(store, "xtmapper-2026-10-22.log"))
        } finally {
            foreign.close()
        }
    }

    @Test
    fun `closing after midnight without post-midnight events emits CONTINUED then END`() {
        // Session starts 23:58 local on the 22nd and stays idle across midnight;
        // the process closes cleanly the next morning.
        val clock = MutableClock.at("2026-10-22T21:58:00Z")
        val store = newStore()
        val writer = makeWriter(store, clock, "sess-midnight-close")
        writer.writeSessionStartMarker()

        clock.advanceSeconds(60 * 60 + 1) // → 00:58:01 local on 2026-10-23, no events written
        writer.endSession(clock.instant(), 3_601_000_000_000L, "Clean shutdown", 0)
        writer.close()

        val day1 = LogFiles.readAll(store, "xtmapper-2026-10-22.log")
        val day2 = LogFiles.readAll(store, "xtmapper-2026-10-23.log")
        assertTrue(day1.contains("SESSION START"))
        assertEquals("START is the only marker in day 1", 1, Regex("SESSION START").findAll(day1).count())

        // The new day's file explains the session before ending it.
        assertTrue(day2.contains("SESSION CONTINUED"))
        assertTrue(day2.contains("Session ID: sess-midnight-close"))
        assertTrue(day2.contains("Continuation Time: 2026-10-23T00:58:01.000+02:00"))
        assertTrue(day2.contains("SESSION END"))
        assertTrue("CONTINUED precedes END", day2.indexOf("SESSION CONTINUED") < day2.indexOf("SESSION END"))
        assertEquals(1, Regex("SESSION CONTINUED").findAll(day2).count())
        assertEquals(1, Regex("SESSION END").findAll(day2).count())

        // The clean close is recognizable by the next session's detection.
        assertFalse(writer.unavailable)
    }

    @Test
    fun `previous session detection distinguishes closed and unclean and first`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()
        assertEquals(SessionState.DISPOSITION_FIRST, SessionState.previousDisposition(store, null))

        val first = makeWriter(store, clock, "sess-1")
        first.writeSessionStartMarker()
        // No END written yet → the running session is unclean from a restart's view.
        assertEquals(SessionState.DISPOSITION_UNCLEAN, SessionState.previousDisposition(store, null))
        first.endSession(clock.instant(), 1_000, "Clean shutdown", 0)
        first.close()
        assertEquals(SessionState.DISPOSITION_CLOSED, SessionState.previousDisposition(store, null))

        // Role scoping: a session of another role never influences this role's state.
        val rootWriter = makeWriter(store, clock, "sess-root", role = "root")
        rootWriter.writeSessionStartMarker()
        assertEquals(SessionState.DISPOSITION_CLOSED, SessionState.previousDisposition(store, null))
        assertEquals(SessionState.DISPOSITION_UNCLEAN, SessionState.previousDisposition(store, "root"))
    }

    @Test
    fun `existing foreign files in the directory are never touched`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val dir = tmp.newFolder()
        dir.resolve("release-notes.txt").writeText("do not touch")
        dir.resolve("xtmapper.zip").writeText("some zip")
        val store = FileSystemLogStore(dir)
        val writer = makeWriter(store, clock, "sess-f")
        writer.writeSessionStartMarker()
        writer.write(listOf(record(clock)))
        val names = store.listFiles().map { it.name }.toSet()
        assertTrue(names.contains("release-notes.txt"))
        assertTrue(names.contains("xtmapper.zip"))
        assertEquals("do not touch", dir.resolve("release-notes.txt").readText())
        writer.close()
    }

    @Test
    fun `new sessions never reuse an already-existing part file`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = newStore()
        val smallCap: LoggingConfig = LoggingConfig(queueCapacity = 8, maxFileBytes = 120, maxPartsPerDay = 8)
        val a = makeWriter(store, clock, "sess-a", config = smallCap)
        a.writeSessionStartMarker()
        repeat(3) { a.write(listOf(record(clock, "a$it"))) }
        a.close()
        val partA = store.listFiles().map { it.name }.filter { it.contains("-part") }.sorted()

        val b = makeWriter(store, clock, "sess-b", config = smallCap)
        b.writeSessionStartMarker()
        repeat(3) { b.write(listOf(record(clock, "b$it"))) }
        b.close()
        val partB = store.listFiles().map { it.name }.filter { it.contains("-part") }.sorted()

        assertTrue("session B continues after session A files", partB.size >= partA.size)
        // No file was overwritten: content of the first session survives intact.
        val baseText = LogFiles.readAll(store, "xtmapper-2026-10-22.log")
        assertTrue(baseText.contains("Session ID: sess-a") || partA.isNotEmpty())
        val wholeLog = store.listFiles().joinToString("") { f -> LogFiles.readAll(store, f.name) }
        assertTrue(wholeLog.contains("Session ID: sess-a"))
        assertTrue(wholeLog.contains("Session ID: sess-b"))
    }
}
