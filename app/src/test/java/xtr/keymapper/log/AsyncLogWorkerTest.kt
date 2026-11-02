package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.TimeUnit

class AsyncLogWorkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(message: String): LogRecord = LogRecord(
        time = Instant.parse("2026-10-22T09:00:00Z"), level = Level.INFO, tag = "t",
        message = message, pid = 1, role = "test", threadName = "main", threadId = 1,
        sessionId = "s-worker",
    )

    private val failingStore = object : LogStore {
        override fun listFiles() = emptyList<LogFileInfo>()
        override fun openAppendWriter(name: String): LogFileWriter =
            throw java.io.IOException("failing store")
        override fun readTail(name: String, maxLines: Int) = emptyList<String>()
        override fun delete(name: String) = false
    }

    private fun buildWorker(
        store: LogStore,
        capacity: Int = 16,
        batchMaxRecords: Int = 256,
    ): Triple<SessionLogWriter, LoggingQueue, AsyncLogWorker> {
        val clock = MutableClock.at("2026-10-22T09:00:00Z")
        val config = LoggingConfig(queueCapacity = capacity, batchMaxRecords = batchMaxRecords)
        val writer = SessionLogWriter(
            store, role = null, config = config,
            clock = clock, zone = clock.zoneId, sessionId = "s-worker", onEvent = {},
        )
        writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
        writer.writeSessionStartMarker()
        val queue = LoggingQueue(capacity)
        val worker = AsyncLogWorker(queue, config, writer, "test")
        return Triple(writer, queue, worker)
    }

    @Test
    fun `flush drains all offered records within the bound`() {
        // Capacity above the offered count: drop-oldest must not lose anything.
        val (writer, queue, worker) = buildWorker(FileSystemLogStore(tmp.newFolder()), capacity = 64)
        worker.start()
        repeat(32) { queue.offer(record("m$it")) }
        assertTrue("drained in time", worker.flush(5_000))
        assertEquals(0, queue.size)
        assertEquals(0, queue.pendingWrites.get())

        val text = LogFiles.readAll(writer.store, "xtmapper-2026-10-22.log")
        assertTrue(text.contains("message=\"m0\""))
        assertTrue(text.contains("message=\"m31\""))
        worker.close(2_000)
    }

    @Test
    fun `records land in the daily file in fifo order after the start marker`() {
        val (writer, queue, worker) = buildWorker(FileSystemLogStore(tmp.newFolder()), capacity = 64)
        worker.start()
        repeat(20) { queue.offer(record("ordered-$it")) }
        assertTrue(worker.flush(5_000))

        val text = LogFiles.readAll(writer.store, "xtmapper-2026-10-22.log")
        val idx0 = text.indexOf("message=\"ordered-0\"")
        val idx19 = text.indexOf("message=\"ordered-19\"")
        val idxMarker = text.indexOf("SESSION START")
        assertTrue("marker precedes records", idxMarker < idx0)
        assertTrue("fifo order kept", idx0 < idx19)
        worker.close(2_000)
    }

    @Test
    fun `worker thread terminates deterministically after close`() {
        val (_, _, worker) = buildWorker(FileSystemLogStore(tmp.newFolder()))
        worker.start()
        val thread = worker.threadReference()
        assertSameThread(thread, worker)
        worker.close(2_000)
        // join already happened inside close; joining again returns immediately.
        thread.join(4_000)
        assertFalse(thread.isAlive)
    }

    private fun assertSameThread(thread: Thread, worker: AsyncLogWorker) {
        assertTrue(thread === worker.threadReference())
    }

    @Test
    fun `failing store marks the sink unavailable and keeps the worker alive`() {
        val (writer, _, worker) = buildWorker(failingStore)
        worker.start()
        repeat(5) { worker.queue.offer(record("x$it")) }
        assertTrue(worker.flush(2_000))
        assertTrue(writer.unavailable)
        assertTrue(worker.threadReference().isAlive)
        worker.close(2_000)
        assertFalse(worker.threadReference().isAlive)
    }

    @Test
    fun `a failed write batch is uncertain then unavailable records are dropped`() {
        val failingHardStore = object : LogStore {
            override fun listFiles() = emptyList<LogFileInfo>()
            override fun openAppendWriter(name: String): LogFileWriter = object : LogFileWriter {
                private var calls = 0
                override val writtenBytes = 0L
                override fun write(chunk: ByteArray) {
                    if (++calls > 1) throw SimulatedWriteError("sink rejected the batch")
                }
                override fun close() {}
            }
            override fun readTail(name: String, maxLines: Int) = emptyList<String>()
            override fun delete(name: String) = false
        }
        val (_, queue, worker) = buildWorker(failingHardStore)
        repeat(3) { queue.offer(record("f$it")) }
        worker.start()
        assertTrue("flush completes after failure accounting", worker.flush(2_000))
        assertEquals(0, worker.droppedCount)
        assertEquals(3, worker.uncertainCount)
        assertEquals(0, queue.pendingWrites.get())
        assertEquals(0, queue.size)
        assertTrue(worker.threadReference().isAlive)

        queue.offer(record("f-after"))
        assertTrue(worker.flush(2_000))
        assertEquals(1, worker.droppedCount)
        assertEquals(3, worker.uncertainCount)
        worker.close(2_000)
        assertFalse(worker.threadReference().isAlive)
    }

    @Test
    fun `fail-soft absorbed failures count as dropped, not written`() {
        val (writer, _, worker) = buildWorker(failingStore)
        worker.start()
        repeat(4) { worker.queue.offer(record("soft$it")) }
        assertTrue(worker.flush(2_000))
        assertTrue(writer.unavailable)
        assertEquals("absorbed records reported as dropped", 4, worker.droppedCount)
        assertEquals(0, worker.queue.pendingWrites.get())
        worker.close(2_000)
    }

    @Test
    fun `batch construction failure settles the polled record as uncertain`() {
        // drainUpTo(-1) throws before `batch` is assigned; the polled credit must not orphan.
        val (_, queue, worker) = buildWorker(FileSystemLogStore(tmp.newFolder()), batchMaxRecords = 0)
        queue.offer(record("crash-me"))
        worker.start()
        assertTrue("flush drains despite the dead worker", worker.flush(2_000))
        assertEquals(0, queue.pendingWrites.get())
        assertEquals(0, queue.size)
        assertEquals(1, worker.uncertainCount)
        assertEquals(0, worker.droppedCount)
        assertTrue("worker closes successfully", worker.close(2_000))
        worker.threadReference().join(2_000)
        assertFalse(worker.threadReference().isAlive)
    }

    private class SimulatedWriteError(message: String) : Error(message)

    @Test
    fun `flush times out instead of hanging when the sink is stuck`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stuckStore = object : LogStore {
            override fun listFiles() = emptyList<LogFileInfo>()
            override fun openAppendWriter(name: String): LogFileWriter = object : LogFileWriter {
                override val writtenBytes = 0L
                override fun write(chunk: ByteArray) {
                    if (chunk.toString(Charsets.UTF_8).contains("message=\"slow\"")) {
                        entered.countDown()
                        release.await()
                    }
                }
                override fun close() {}
            }
            override fun readTail(name: String, maxLines: Int) = emptyList<String>()
            override fun delete(name: String) = false
        }
        val (_, queue, worker) = buildWorker(stuckStore)
        worker.start()
        queue.offer(record("slow"))
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            assertFalse("bound respected", worker.flush(120))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("timeout ≈ bound", elapsedMs in 100..4_000)
            assertFalse(worker.stopDraining(50))
            assertTrue(runCatching { worker.closeFile() }.exceptionOrNull() is IllegalStateException)
        } finally {
            release.countDown()
            assertTrue(worker.close(2_000))
            worker.threadReference().join(2_000)
        }
    }
}
