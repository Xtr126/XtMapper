package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LoggingAccountingRegressionTest {
    private val clock = MutableClock.at("2026-10-22T09:00:00Z")

    private fun record(message: String) = LogRecord(
        clock.instant(), Level.INFO, "test", message, pid = 1, role = "app", threadName = "test",
    )

    private fun writer(sink: LogFileWriter): SessionLogWriter {
        val store = object : LogStore {
            override fun listFiles() = emptyList<LogFileInfo>()
            override fun openAppendWriter(name: String) = sink
            override fun readTail(name: String, maxLines: Int) = emptyList<String>()
            override fun delete(name: String) = false
        }
        return SessionLogWriter(store, null, LoggingConfig(), clock, clock.zoneId, "accounting").apply {
            configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
            writeSessionStartMarker()
        }
    }

    @Test
    fun `timed out stop discards queued records but preserves the executing record`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val writing = AtomicBoolean()
        val unsafeClose = AtomicBoolean()
        val sink = object : LogFileWriter {
            override val writtenBytes = 0L
            override fun write(chunk: ByteArray) {
                if (!chunk.toString(Charsets.UTF_8).contains("message=\"blocked\"")) return
                writing.set(true)
                try {
                    entered.countDown()
                    release.await()
                } finally {
                    writing.set(false)
                }
            }
            override fun close() {
                if (writing.get()) unsafeClose.set(true)
                closed.countDown()
            }
        }
        val queue = LoggingQueue(2)
        val worker = AsyncLogWorker(queue, LoggingConfig(batchMaxRecords = 1), writer(sink), "test")
        worker.start()
        queue.offer(record("blocked"))
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(5) { queue.offer(record("queued-$it")) }
            assertEquals(3, queue.droppedCount.get())
            assertEquals(3, queue.pendingWrites.get())
            assertFalse(worker.stopDraining(20))
            assertEquals("only queued records are discarded by stop", 0, queue.size)
            assertEquals("blocked work retains its credit", 1, queue.pendingWrites.get())
            assertEquals(5, queue.droppedCount.get())
            assertFalse(worker.flush(0))
            queue.offer(record("late"))
            assertEquals("closed admission adds no credit", 1, queue.pendingWrites.get())
            assertFalse(worker.stopDraining(0))
            assertEquals(1L, closed.count)
            assertFalse(unsafeClose.get())
        } finally {
            release.countDown()
            worker.close(2_000)
            worker.threadReference().join(2_000)
        }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(0, queue.pendingWrites.get())
        assertFalse(unsafeClose.get())
    }

    @Test
    fun `a partially written batch is not reported as definitely lost`() {
        val output = StringBuffer()
        val sink = object : LogFileWriter {
            override val writtenBytes: Long get() = output.length.toLong()
            override fun write(chunk: ByteArray) {
                val text = chunk.toString(Charsets.UTF_8)
                if (text.contains("message=\"second\"")) {
                    output.append(text.take(text.length / 2))
                    throw IOException("partial write")
                }
                output.append(text)
            }
            override fun close() {}
        }
        val queue = LoggingQueue(4)
        val worker = AsyncLogWorker(queue, LoggingConfig(batchMaxRecords = 4), writer(sink), "test")
        listOf("first", "second", "third").forEach { queue.offer(record(it)) }
        worker.start()
        try {
            assertTrue(worker.flush(2_000))
            assertTrue(output.contains("message=\"first\""))
            assertFalse(output.contains("message=\"third\""))
            assertEquals("the attempted batch is uncertain, not exact loss", 0, worker.droppedCount)
            assertEquals(3, worker.uncertainCount)
            queue.offer(record("known unavailable"))
            assertTrue(worker.flush(2_000))
            assertEquals("no attempted write means a definite rejection", 1, worker.droppedCount)
        } finally {
            worker.close(2_000)
        }
    }

    @Test
    fun `settlement cannot erase queued credit or count the same batch twice`() {
        val queue = LoggingQueue(4)
        queue.offer(record("first"))
        assertTrue(runCatching { queue.markProcessed(1) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, queue.pendingWrites.get())
        assertEquals(1, queue.drainUpTo(4)!!.size)
        queue.markFailed(1)
        assertTrue(runCatching { queue.markFailed(1) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, queue.droppedCount.get())
        assertEquals(0, queue.pendingWrites.get())
    }
}
