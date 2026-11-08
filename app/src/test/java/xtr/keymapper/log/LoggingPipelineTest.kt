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
    fun `retention deletes at most eight files per run and defers the remainder`() {
        val store = FileSystemLogStore(tmp.newFolder())
        val config = LoggingConfig(maxAgeDays = 14, maxFiles = 8)
        (1..10).forEach {
            store.openAppendWriter("xtmapper-2026-08-%02d.log".format(java.util.Locale.ROOT, it)).use { w ->
                w.write("old\n".toByteArray())
            }
        }
        // A current-day file anchors the newest-date protection so all ten old files are
        // deletable; the pipeline holds no writer, so none of them is active.
        store.openAppendWriter("xtmapper-2026-10-23.log").use { w -> w.write("today\n".toByteArray()) }
        val pipeline = TestPipelineFactory(store, MutableClock.at("2026-10-23T09:00:00Z")).start()
        try {
            val today = java.time.LocalDate.of(2026, 10, 23)
            pipeline.applyRetention(store, config, today)
            assertEquals(
                "at most eight deletions per run, oldest first",
                setOf("xtmapper-2026-08-09.log", "xtmapper-2026-08-10.log", "xtmapper-2026-10-23.log"),
                store.listFiles().map { it.name }.toSet(),
            )
            pipeline.applyRetention(store, config, today)
            assertEquals(
                "the deferred remainder lands in a later run",
                listOf("xtmapper-2026-10-23.log"),
                store.listFiles().map { it.name },
            )
        } finally {
            pipeline.close("Clean shutdown")
        }
    }

    @Test
    fun `retention rechecks eligibility at delete time and leaves a newly active file alone`() {
        val store = FileSystemLogStore(tmp.newFolder())
        val config = LoggingConfig(maxAgeDays = 14, maxFiles = 8)
        listOf("xtmapper-2026-08-01.log", "xtmapper-2026-08-02.log").forEach {
            store.openAppendWriter(it).use { w -> w.write("old\n".toByteArray()) }
        }
        val queried = intArrayOf(0)
        val pipeline = TestPipelineFactory(store, MutableClock.at("2026-10-23T09:00:00Z")).start()
        try {
            pipeline.applyRetention(store, config, java.time.LocalDate.of(2026, 10, 23), activeNames = {
                // Enumeration pass sees nothing active; the delete-time recheck is invoked
                // again per candidate and now reports the first file as active.
                if (queried[0]++ == 0) emptySet() else setOf("xtmapper-2026-08-01.log")
            })
            assertTrue("the newly active candidate is never removed", store.listFiles().any { it.name == "xtmapper-2026-08-01.log" })
            assertFalse("the recheck did not spare the fully eligible candidate", store.listFiles().any { it.name == "xtmapper-2026-08-02.log" })
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

        SessionLogWriter(store, null, LoggingConfig(), clock, clock.zoneId, "unfinished").use { unfinished ->
            unfinished.configure(SessionState.DISPOSITION_CLOSED, SessionMetadata(), clock.instant())
            unfinished.writeSessionStartMarker()
        }

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

        assertTrue("worker took the first record", firstWrite.await(3, java.util.concurrent.TimeUnit.SECONDS))

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
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stuckStore = object : LogStore {
            override fun listFiles() = emptyList<LogFileInfo>()
            override fun openAppendWriter(name: String): LogFileWriter = object : LogFileWriter {
                override val writtenBytes = 0L
                override fun write(chunk: ByteArray) {
                    if (chunk.toString(Charsets.UTF_8).contains("message=\"stuck record\"")) {
                        entered.countDown()
                        release.await()
                    }
                }
                override fun close() {}
            }
            override fun readTail(name: String, maxLines: Int) = emptyList<String>()
            override fun delete(name: String) = false
        }
        val factory = TestPipelineFactory(stuckStore, clock)
        val pipeline = factory.start()
        pipeline.info("T", "stuck record")
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            assertFalse(pipeline.flush(80))
            val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(elapsedMs in 60..4_000)
        } finally {
            release.countDown()
            assertTrue(pipeline.close("Clean shutdown"))
        }
    }

    @Test
    fun `close returns while worker owns a blocked file lock`() {
        val writeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val endMarkerWritten = java.util.concurrent.CountDownLatch(1)
        val blockEventOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val sinkLock = java.util.concurrent.locks.ReentrantLock()
        val output = StringBuilder()
        var currentName: String? = null

        fun <T> withSinkLock(block: () -> T): T {
            sinkLock.lock()
            try {
                return block()
            } finally {
                sinkLock.unlock()
            }
        }

        val sink = object : LogFileWriter {
            override val writtenBytes: Long
                get() = withSinkLock { output.length.toLong() }

            override fun write(chunk: ByteArray) {
                sinkLock.lock()
                try {
                    val text = String(chunk, Charsets.UTF_8)
                    if (text.contains("message=\"blocked event\"") &&
                        blockEventOnce.compareAndSet(false, true)
                    ) {
                        writeEntered.countDown()
                        releaseWrite.await()
                    }
                    output.append(text)
                    if (text.contains("SESSION END")) endMarkerWritten.countDown()
                } finally {
                    sinkLock.unlock()
                }
            }

            override fun close() {
                withSinkLock { Unit }
            }
        }

        val store = object : LogStore {
            override fun listFiles(): List<LogFileInfo> = withSinkLock {
                val name = currentName
                if (name == null) emptyList() else listOf(LogFileInfo(name, output.length.toLong()))
            }

            override fun openAppendWriter(name: String): LogFileWriter {
                currentName = name
                return sink
            }

            override fun readTail(name: String, maxLines: Int): List<String> = withSinkLock {
                output.toString().lineSequence().toList().takeLast(maxLines)
            }

            override fun delete(name: String) = false
        }

        val clock = MutableClock.at("2026-01-01T00:00:00Z")
        val factory = TestPipelineFactory(
            store, clock, config = LoggingConfig(flushTimeoutMillis = 100)
        )
        val pipeline = factory.start()
        pipeline.info("Test", "blocked event")
        assertTrue("worker entered the deliberately blocked write", writeEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))

        val closeReturned = java.util.concurrent.CountDownLatch(2)
        repeat(2) { index ->
            Thread({
                pipeline.close("bounded close")
                closeReturned.countDown()
            }, "test-log-close-$index").apply {
                isDaemon = true
                start()
            }
        }

        val returnedBeforeRelease = closeReturned.await(3_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Always unblock the fake sink so a failing regression test cleans up.
        releaseWrite.countDown()
        val eventuallyReturned = closeReturned.await(3_500, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue("close must not wait on the worker's file lock", returnedBeforeRelease)
        assertTrue("both concurrent close() callers must return", eventuallyReturned)
        assertTrue(
            "deferred cleanup writes SESSION END after the worker exits",
            endMarkerWritten.await(3, java.util.concurrent.TimeUnit.SECONDS)
        )
        assertEquals(
            "concurrent close() calls must emit exactly one SESSION END marker",
            1,
            withSinkLock { output.toString().split("SESSION END").size - 1 }
        )
    }

}
