package xtr.keymapper.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList

class XtLoggerNativeSubmissionTest {
    private val clock = MutableClock.at("2026-11-01T12:00:00Z")
    private val logcatRecords = CopyOnWriteArrayList<LogRecord>()

    private class RecordingStore : LogStore {
        val chunks = CopyOnWriteArrayList<String>()
        override fun listFiles(): List<LogFileInfo> = emptyList()
        override fun openAppendWriter(name: String): LogFileWriter =
            object : LogFileWriter {
                override val writtenBytes: Long get() = 0L
                override fun write(chunk: ByteArray) { chunks.add(chunk.toString(Charsets.UTF_8)) }
                override fun close() {}
            }
        override fun readTail(name: String, maxLines: Int): List<String> = emptyList()
        override fun delete(name: String): Boolean = false
    }

    private fun stateField(name: String): Field =
        XtLogger::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun logNative(priority: Int, source: Int, message: String?): Int {
        val method = XtLogger::class.java.getDeclaredMethod(
            "logNative", Integer.TYPE, Integer.TYPE, String::class.java,
        )
        method.isAccessible = true
        return method.invoke(XtLogger, priority, source, message) as Int
    }

    private fun install(
        config: LoggingConfig = LoggingConfig(queueCapacity = 16, flushTimeoutMillis = 100),
        store: LogStore = RecordingStore(),
    ): LoggingPipeline {
        val pipeline = LoggingPipeline(
            role = Role.APP,
            sessionId = "native-probe",
            store = store,
            config = config,
            clock = clock,
            zone = clock.zoneId,
            startTime = clock.instant(),
            durationNanos = { 0 },
            logcat = { logcatRecords.add(it) },
            pruneRetention = false,
        )
        pipeline.start()
        stateField("pipeline").set(XtLogger, pipeline)
        stateField("initializationRole").set(XtLogger, Role.APP)
        return pipeline
    }

    @After
    fun tearDown() {
        runCatching {
            (stateField("pipeline").get(XtLogger) as? LoggingPipeline)?.close("Teardown")
        }
        for (name in listOf("pipeline", "initializationRole", "retiringOwner")) {
            runCatching { stateField(name).set(XtLogger, null) }
        }
        runCatching { stateField("fatalEventRecorded").set(XtLogger, false) }
        logcatRecords.clear()
    }

    @Test
    fun `native submissions without an active owner are closed and never initialize storage`() {
        assertEquals(LogSubmission.CLOSED.nativeCode, logNative(5, 1, "keyboard probe"))
        assertNull("the closed result must not start a replacement session", stateField("pipeline").get(XtLogger))
        assertNull("no role is established by a rejected native submission", XtLogger.currentRole)
        assertFalse(XtLogger.isInitialized)
    }

    private fun nativeRecords(): List<LogRecord> =
        logcatRecords.filter { it.tag == "Mouse" || it.tag == "Keyboard" }

    @Test
    fun `native submissions reject invalid priority source and payload without queue effect`() {
        val pipeline = install()
        try {
            for (value in intArrayOf(1, 7, 0)) {
                assertEquals(
                    "priority $value is outside Android 2..6",
                    LogSubmission.INVALID.nativeCode, logNative(value, 0, "mouse probe"),
                )
            }
            for (source in intArrayOf(-1, 2, 7)) {
                assertEquals(
                    "source $source is not a known producer",
                    LogSubmission.INVALID.nativeCode, logNative(3, source, "mouse probe"),
                )
            }
            assertEquals(LogSubmission.INVALID.nativeCode, logNative(4, 1, null))
            assertEquals(
                "513 UTF-16 chars exceeds the bounded payload",
                LogSubmission.INVALID.nativeCode, logNative(4, 1, "x".repeat(513)),
            )
            assertFalse("native submissions never take the fatal path", XtLogger.hasFatalEvent)
            assertTrue(nativeRecords().isEmpty())
        } finally {
            pipeline.close("Reject teardown")
            stateField("pipeline").set(XtLogger, null)
        }
    }

    @Test
    fun `active owner admits native submissions and maps priority and source`() {
        val pipeline = install(
            config = LoggingConfig(minLevelFile = Level.VERBOSE, minLevelLogcat = Level.VERBOSE, queueCapacity = 16),
        )
        try {
            assertEquals(LogSubmission.ACCEPTED.nativeCode, logNative(5, 0, "mouse warn"))
            assertEquals(LogSubmission.ACCEPTED.nativeCode, logNative(6, 1, "keyboard error"))
            assertEquals(LogSubmission.ACCEPTED.nativeCode, logNative(2, 0, "mouse verbose"))
            assertEquals(
                "512 UTF-16 chars is within the bound",
                LogSubmission.ACCEPTED.nativeCode, logNative(5, 0, "y".repeat(512)),
            )
            val native = nativeRecords()
            assertEquals(4, native.size)
            assertEquals(Level.WARN, native[0].level)
            assertEquals("Mouse", native[0].tag)
            assertEquals("mouse warn", native[0].message)
            assertEquals(Level.ERROR, native[1].level)
            assertEquals("Keyboard", native[1].tag)
            assertEquals("keyboard error", native[1].message)
            assertEquals(Level.VERBOSE, native[2].level)
            assertEquals("Mouse", native[2].tag)
            assertEquals(512, native[3].message.length)
        } finally {
            pipeline.close("Mapping teardown")
            stateField("pipeline").set(XtLogger, null)
        }
    }

    @Test
    fun `native payload bounds count standard utf8 bytes not utf16 units`() {
        val pipeline = install()
        try {
            val exact = listOf("x".repeat(512), "é".repeat(256), "数".repeat(170) + "aa", "😀".repeat(128))
            for (message in exact) {
                assertEquals(512, message.toByteArray(Charsets.UTF_8).size)
                assertEquals(LogSubmission.ACCEPTED.nativeCode, logNative(4, 0, message))
            }
            for (message in exact.map { it + "x" } + "") {
                val before = nativeRecords().size
                assertEquals(LogSubmission.INVALID.nativeCode, logNative(4, 1, message))
                assertEquals("invalid payload cannot reach a sink", before, nativeRecords().size)
            }
        } finally {
            pipeline.close("UTF8 teardown")
        }
    }

    @Test
    fun `native info persists and native debug is file filtered in default release`() {
        val store = RecordingStore()
        val pipeline = install(LoggingConfig.DEFAULT_RELEASE, store)
        assertEquals(LogSubmission.FILTERED.nativeCode, logNative(3, 0, "release-debug-probe"))
        assertEquals(LogSubmission.ACCEPTED.nativeCode, logNative(4, 1, "release-info-probe"))
        assertTrue(pipeline.close("Release teardown"))
        val persisted = store.chunks.joinToString("")
        assertTrue(persisted.contains("release-info-probe"))
        assertFalse(persisted.contains("release-debug-probe"))
    }

    @Test
    fun `filtered native severities report the filtered outcome without admission`() {
        val pipeline = install(
            config = LoggingConfig(minLevelFile = Level.INFO, minLevelLogcat = Level.INFO, queueCapacity = 16),
        )
        try {
            assertEquals(LogSubmission.FILTERED.nativeCode, logNative(3, 1, "keyboard debug"))
            assertEquals(LogSubmission.ACCEPTED.nativeCode, logNative(4, 1, "keyboard info"))
            val native = nativeRecords()
            assertEquals("only the INFO record crosses logcat", 1, native.size)
            assertEquals("Keyboard", native[0].tag)
            assertEquals("keyboard info", native[0].message)
        } finally {
            pipeline.close("Filter teardown")
            stateField("pipeline").set(XtLogger, null)
        }
    }

    @Test
    fun `stale producers holding a closed owner are rejected by the queue gate`() {
        val pipeline = install()
        pipeline.close("Native test closing owner")
        assertEquals(LogSubmission.CLOSED.nativeCode, logNative(5, 0, "mouse probe"))
        assertEquals(LogSubmission.CLOSED.nativeCode, logNative(4, 1, "keyboard probe"))
        assertTrue(pipeline.isClosed)
        stateField("pipeline").set(XtLogger, null)
    }
}
