package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LoggingOwnershipRegressionTest {
    private val clock = MutableClock.at("2026-10-22T09:00:00Z")

    private class ProbeStore : LogStore {
        val calls = AtomicInteger()
        val output = StringBuffer()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var blockMessage: String? = null
        var blockEnd = false
        var blockStart = false
        val writing = AtomicBoolean()
        val concurrentClose = AtomicBoolean()

        override fun listFiles(): List<LogFileInfo> {
            calls.incrementAndGet()
            return emptyList()
        }

        override fun openAppendWriter(name: String): LogFileWriter {
            calls.incrementAndGet()
            return object : LogFileWriter {
                override val writtenBytes: Long get() = output.length.toLong()
                override fun write(chunk: ByteArray) {
                    val text = chunk.toString(Charsets.UTF_8)
                    writing.set(true)
                    try {
                        if (blockMessage?.let { text.contains("message=\"$it\"") } == true ||
                            blockEnd && text.contains(SessionMarkers.END) ||
                            blockStart && text.contains(SessionMarkers.START)
                        ) {
                            writeEntered.countDown()
                            releaseWrite.await()
                        }
                        output.append(text)
                    } finally {
                        writing.set(false)
                    }
                }

                override fun close() {
                    if (writing.get()) concurrentClose.set(true)
                    closed.countDown()
                }
            }
        }

        override fun readTail(name: String, maxLines: Int) = emptyList<String>()
        override fun delete(name: String) = false
    }

    private fun pipeline(store: LogStore, beforeAdmission: () -> Unit = {}): LoggingPipeline =
        LoggingPipeline(
            Role.APP, "ownership", store, LoggingConfig(flushTimeoutMillis = 100, batchMaxRecords = 1),
            clock, clock.zoneId, clock.instant(), { 0 }, beforeAdmission = beforeAdmission,
        )

    @Test
    fun `close before start rejects storage work permanently`() {
        val store = ProbeStore()
        val pipeline = pipeline(store)
        pipeline.close()
        try {
            pipeline.start()
            assertEquals(0, store.calls.get())
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `start after close does not reopen the same pipeline`() {
        val store = ProbeStore()
        val pipeline = pipeline(store)
        pipeline.start()
        pipeline.close()
        val calls = store.calls.get()
        pipeline.start()
        assertEquals(calls, store.calls.get())
        assertEquals(1, store.output.toString().split(SessionMarkers.START).size - 1)
    }

    @Test
    fun `producer paused immediately before admission is rejected after completed close`() {
        val store = ProbeStore()
        val beforeOffer = CountDownLatch(1)
        val releaseOffer = CountDownLatch(1)
        val pipeline = pipeline(store) {
            beforeOffer.countDown()
            releaseOffer.await()
        }
        pipeline.start()
        assertTrue(pipeline.flush(2_000))
        val producer = Thread { pipeline.info("Test", "late record") }
        producer.start()
        try {
            assertTrue(beforeOffer.await(2, TimeUnit.SECONDS))
            pipeline.close()
            assertTrue(store.closed.await(2, TimeUnit.SECONDS))
        } finally {
            releaseOffer.countDown()
            producer.join(2_000)
            pipeline.close()
        }
        assertFalse(producer.isAlive)
        assertEquals(0, pipeline.pendingRecords)
        assertTrue("rejection adds no outstanding credit", pipeline.flush(0))
        assertFalse(store.output.contains("late record"))
    }

    @Test
    fun `end marker cannot extend the close wait indefinitely`() {
        val store = ProbeStore().apply { blockEnd = true }
        val pipeline = pipeline(store)
        pipeline.start()
        assertTrue(pipeline.flush(2_000))
        val returned = CountDownLatch(1)
        val closer = Thread { pipeline.close(); returned.countDown() }
        closer.start()
        try {
            assertTrue(store.writeEntered.await(2, TimeUnit.SECONDS))
            assertTrue("close returns with END explicitly unfinished", returned.await(1, TimeUnit.SECONDS))
            assertFalse(store.concurrentClose.get())
            assertEquals(1L, store.closed.count)
        } finally {
            store.releaseWrite.countDown()
            closer.join(2_000)
        }
        assertTrue(store.closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a live worker rejects direct file close`() {
        val store = ProbeStore().apply { blockMessage = "blocked" }
        val config = LoggingConfig(batchMaxRecords = 1)
        val writer = SessionLogWriter(store, null, config, clock, clock.zoneId, "worker")
        writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
        writer.writeSessionStartMarker()
        val queue = LoggingQueue(2)
        val worker = AsyncLogWorker(queue, config, writer, "test")
        worker.start()
        queue.offer(record("blocked"))
        try {
            assertTrue(store.writeEntered.await(2, TimeUnit.SECONDS))
            assertTrue(runCatching { worker.closeFile() }.exceptionOrNull() is IllegalStateException)
            assertFalse(store.concurrentClose.get())
        } finally {
            store.releaseWrite.countDown()
            worker.close(2_000)
        }
    }

    @Test
    fun `session writer close is terminal rather than a rotation`() {
        val store = ProbeStore()
        val writer = SessionLogWriter(store, null, LoggingConfig(), clock, clock.zoneId, "writer")
        writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
        writer.writeSessionStartMarker()
        writer.close()
        val calls = store.calls.get()
        writer.write(listOf(record("after writer close")))
        assertEquals(calls, store.calls.get())
        assertFalse(store.output.contains("after writer close"))
    }

    @Test
    fun `close during start leaves file finalization with the starting worker`() {
        val store = ProbeStore().apply { blockStart = true }
        val pipeline = pipeline(store)
        val starter = Thread { pipeline.start() }
        starter.start()
        try {
            assertTrue(store.writeEntered.await(2, TimeUnit.SECONDS))
            pipeline.close()
            assertFalse(store.concurrentClose.get())
            assertEquals("START is still executing", 1L, store.closed.count)
            pipeline.info("Test", "closed during start")
        } finally {
            store.releaseWrite.countDown()
            starter.join(2_000)
            pipeline.close()
        }
        assertFalse(starter.isAlive)
        assertTrue(store.closed.await(2, TimeUnit.SECONDS))
        assertFalse(store.output.contains("closed during start"))
        val calls = store.calls.get()
        pipeline.start()
        assertEquals(calls, store.calls.get())
    }

    @Test
    fun `worker closed before start cannot create a consumer later`() {
        val store = ProbeStore()
        val config = LoggingConfig()
        val writer = SessionLogWriter(store, null, config, clock, clock.zoneId, "unstarted")
        val queue = LoggingQueue(2)
        val worker = AsyncLogWorker(queue, config, writer, "test")
        worker.close(0)
        try {
            worker.start()
            queue.offer(record("after stop"))
            assertFalse(worker.threadReference().isAlive)
            assertEquals(0, store.calls.get())
            assertEquals(0, queue.pendingWrites.get())
            assertEquals(0, queue.size)
        } finally {
            worker.close(2_000)
        }
    }

    private fun record(message: String) = LogRecord(
        Instant.EPOCH, Level.INFO, "test", message, pid = 1, role = "app", threadName = "test",
    )
}
