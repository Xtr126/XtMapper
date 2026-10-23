package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoggingPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val failingStore = object : LogStore {
        override fun listFiles() = emptyList<LogFileInfo>()
        override fun openAppendWriter(name: String): LogFileWriter =
            throw java.io.IOException("readonly storage")
        override fun readTail(name: String, maxLines: Int) = emptyList<String>()
        override fun delete(name: String) = false
    }

    @Test
    fun `retention cannot delete a foreign process's active file beyond the grace day`() {
        // APP pipeline scans/prunes the shared directory; a shizuku-role file
        // opened weeks ago is still held open by its "foreign" writer handle
        // (same-JVM helper stands in for the other process — the cross-process
        // behavior itself is device-verified). The lock gate must keep it, and
        // closing it must make it prunable again; each scan reports skips once.
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = FileSystemLogStore(tmp.newFolder())
        val factory = TestPipelineFactory(store, clock)
        val pipeline = factory.start()
        try {
            val foreign = store.openAppendWriter("xtmapper-shizuku-2026-10-01.log")
            val today = java.time.LocalDate.of(2026, 10, 23)
            val config = LoggingConfig(maxAgeDays = 14, maxTotalBytes = 8L * 1024 * 1024, maxFiles = 8)

            pipeline.applyRetention(store, config, today)
            assertTrue("active foreign file survives retention", store.listFiles().any { it.name == "xtmapper-shizuku-2026-10-01.log" })
            assertEquals(1, factory.failureEvents.count { it.contains("retention skipped 1 candidate files") })

            foreign.close() // the foreign session ended normally
            pipeline.applyRetention(store, config, today)
            assertFalse("prunable after its writer released", store.listFiles().any { it.name == "xtmapper-shizuku-2026-10-01.log" })
            assertEquals(1, factory.failureEvents.count { it.contains("retention skipped") })
        } finally {
            pipeline.close("Clean shutdown")
        }
    }

    @Test
    fun `session lifecycle produces start, events and end markers in one daily file`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = FileSystemLogStore(tmp.newFolder())
        val factory = TestPipelineFactory(store, clock)
        val pipeline = factory.start()

        pipeline.info("Server", "service started")
        pipeline.warn("Input", "slow event path")
        pipeline.error("Server", "unexpected failure", IllegalStateException("broken"))
        assertTrue(pipeline.flush(5_000))
        pipeline.close("Service stopped")

        val text = LogFiles.readAll(store, "xtmapper-2026-10-22.log")
        assertTrue(text.contains("SESSION START"))
        assertTrue(text.contains("Session ID: test-session-001"))
        assertTrue(text.contains("role=app"))
        assertTrue(text.contains("pid=42"))
        assertTrue(text.contains("message=\"service started\""))
        assertTrue(text.contains("exception=java.lang.IllegalStateException: broken"))
        assertTrue(text.contains("SESSION END"))
        assertTrue(text.contains("Status: Service stopped"))
        // Session id is attached to events for future crash-report tooling.
        assertTrue(text.contains("session=test-session-001"))
        assertFalse(text.contains("Dropped:"))
    }

    @Test
    fun `logcat sink is functional and independent from a dead file sink`() {
        val clock = MutableClock.at("2026-01-01T00:00:00Z")
        val factory = TestPipelineFactory(failingStore, clock)
        val pipeline = factory.start()

        assertEquals(1, factory.logcatRecords.size) // the lifecycle INFO of start()
        pipeline.info("Server", "still visible on logcat")
        pipeline.error("Server", "boom", RuntimeException("x"))

        // File sink failed exactly once, reported on the independent channel.
        assertTrue(factory.failureEvents.any { it.contains("file sink unavailable") })
        assertTrue(factory.failureEvents.count { it.contains("file sink unavailable") } == 1)

        // Logcat kept receiving records after the failure.
        assertTrue(factory.logcatRecords.any { it.message == "still visible on logcat" })
        assertTrue(factory.logcatRecords.any { it.message == "boom" })
        pipeline.close()
    }

    @Test
    fun `minimum levels filter logcat and file sinks independently`() {
        val clock = MutableClock.at("2026-01-01T00:00:00Z")
        val store = FileSystemLogStore(tmp.newFolder())
        val config = LoggingConfig(
            queueCapacity = 16,
            minLevelFile = Level.WARN,
            minLevelLogcat = Level.INFO,
        )
        val factory = TestPipelineFactory(store, clock, config = config)
        val pipeline = factory.start()

        pipeline.verbose("T", "verbose")
        pipeline.debug("T", "debug")
        pipeline.info("T", "info-only-logcat")
        pipeline.warn("T", "warn-both")
        assertTrue(pipeline.flush(5_000))
        pipeline.close()

        // Logcat route: from INFO upwards.
        val levels = factory.logcatRecords.filter { it.tag == "T" }.map { it.level }
        assertTrue(levels.none { it == Level.VERBOSE })
        assertTrue(levels.none { it == Level.DEBUG })
        assertTrue(levels.contains(Level.INFO))
        assertTrue(levels.contains(Level.WARN))

        // File route: only WARN supplied this session.
        val text = LogFiles.readAll(store, "xtmapper-2026-01-01.log")
        assertFalse(text.contains("info-only-logcat"))
        assertTrue(text.contains("warn-both"))
        assertFalse(text.contains("verbose"))
    }

    @Test
    fun `a clean previous session is detected and a killed one is reported unclean`() {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val store = FileSystemLogStore(tmp.newFolder())

        val factory = TestPipelineFactory(store, clock)
        val first = factory.start()
        first.info("Server", "first run")
        assertTrue(first.flush(5_000))
        first.close("Clean shutdown")

        val factory2 = TestPipelineFactory(store, clock)
        val second = factory2.start()
        second.info("Server", "second run")
        assertTrue(second.flush(5_000))
        second.close("Clean shutdown")

        val text = LogFiles.readAll(store, "xtmapper-2026-10-22.log")
        // First session had no previous state; the second saw the clean END.
        assertTrue(text.contains("Previous Session: First session"))
        assertTrue(text.contains("Previous Session: Closed cleanly"))

        // Third process instance closes WITHOUT its END (simulated kill): the
        // fourth instance must classify the third as not closed cleanly.
        val factory3 = TestPipelineFactory(store, clock)
        val third = factory3.start()
        third.info("Server", "third run")
        assertTrue(third.flush(5_000))
        // Simulated process death: in production the kernel releases the
        // POSIX lock at process exit while no SESSION END gets written.
        // Within one JVM the equivalent is closing the dead writer's file
        // handle (releases its lock, writes no marker).
        third.sessionWriter.close()

        val factory4 = TestPipelineFactory(store, clock)
        val fourth = factory4.start()
        assertTrue(fourth.flush(5_000))
        fourth.close("Clean shutdown")
        val textAfterFourth = LogFiles.readAll(store, "xtmapper-2026-10-22.log")
        assertTrue(textAfterFourth.contains("Previous Session: Did not close cleanly"))
    }

    @Test
    fun `queue overflow is reported once and counted, and appears in the session end`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val firstWrite = java.util.concurrent.CountDownLatch(1)
        val firstWriteSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        val written = StringBuilder()
        val blockingStore = object : LogStore {
            override fun listFiles() = emptyList<LogFileInfo>()
            override fun openAppendWriter(name: String): LogFileWriter = object : LogFileWriter {
                private var bytes = 0L
                override val writtenBytes: Long get() = bytes
                override fun write(chunk: ByteArray) {
                    val text = String(chunk, Charsets.UTF_8)
                    // Event chunks carry message="..."; marker blocks do not. The
                    // worker's FIRST event write blocks: that record is in flight,
                    // and the test only releases it after the burst.
                    if (text.contains("message=\"")) {
                        if (firstWriteSeen.compareAndSet(false, true)) {
                            firstWrite.countDown()
                        }
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    written.append(text)
                    bytes += chunk.size
                }
                override fun close() {}
            }
            override fun readTail(name: String, maxLines: Int) = emptyList<String>()
            override fun delete(name: String) = false
        }
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val config = LoggingConfig(queueCapacity = 2)
        val factory = TestPipelineFactory(blockingStore, clock, config = config)
        val pipeline = factory.start()

        // Deterministic setup: the worker is known to hold one record in flight
        // (blocked in the store write) before the burst begins.
        assertTrue("worker took the first record", firstWrite.await(3, java.util.concurrent.TimeUnit.SECONDS))

        // Capacity 2 holds exactly two queued records plus one in flight, so
        // 8 of the 10 burst records overflow (drop-oldest).
        repeat(10) { pipeline.info("Burst", "event $it") }
        assertEquals(8, pipeline.droppedCount)
        assertEquals(1, factory.failureEvents.count { it.contains("records are being dropped") })

        release.countDown()
        assertTrue(pipeline.flush(5_000))
        pipeline.close("Clean shutdown")

        assertTrue(written.contains("Dropped: 8"))
        assertTrue(written.contains("SESSION START"))
    }

    @Test
    fun `records after close still reach logcat but not the file sink`() {
        val clock = MutableClock.at("2026-01-01T00:00:00Z")
        val store = FileSystemLogStore(tmp.newFolder())
        val factory = TestPipelineFactory(store, clock)
        val pipeline = factory.start()
        pipeline.info("T", "before")
        assertTrue(pipeline.flush(5_000))
        pipeline.close("Clean shutdown")

        pipeline.info("T", "after close")
        val text = LogFiles.readAll(store, "xtmapper-2026-01-01.log")
        assertTrue(text.contains("message=\"before\""))
        assertFalse(text.contains("message=\"after close\""))
        assertTrue(factory.logcatRecords.any { it.message == "after close" })
    }

    @Test
    fun `flush is bounded and returns false when the sink is stuck`() {
        val clock = MutableClock.at("2026-01-01T00:00:00Z")
        val stuckStore = object : LogStore {
            override fun listFiles() = emptyList<LogFileInfo>()
            override fun openAppendWriter(name: String): LogFileWriter = object : LogFileWriter {
                override val writtenBytes = 0L
                override fun write(chunk: ByteArray) {
                    Thread.sleep(8_000)
                }
                override fun close() {}
            }
            override fun readTail(name: String, maxLines: Int) = emptyList<String>()
            override fun delete(name: String) = false
        }
        val factory = TestPipelineFactory(stuckStore, clock)
        val pipeline = factory.start() // start marker write fires the stuck sleep
        pipeline.info("T", "stuck record")
        val startedAt = System.nanoTime()
        val ok = pipeline.flush(80)
        val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertFalse(ok)
        assertTrue(elapsedMs in 60..4_000)
        pipeline.close("Clean shutdown") // bounded internally; must not hang
    }
}
