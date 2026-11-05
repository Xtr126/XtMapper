package xtr.keymapper.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncLogWorker(
    val queue: LoggingQueue,
    private val config: LoggingConfig,
    val writer: SessionWriter,
    private val roleTag: String,
    private val onStart: () -> Unit = {},
    private val onStop: (String) -> Unit = {},
    private val afterClose: () -> Unit = {},
) {
    private val lifecycle = Any()
    private var launched = false
    @Volatile private var stopStatus: String? = null
    private val lastError = AtomicReference<Throwable?>(null)
    private val fileClosed = AtomicBoolean(false)
    private val initialized = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val thread = Thread({ loop() }, "xtlogger-$roleTag").apply { isDaemon = true }

    val pendingRecords: Int get() = queue.size

    val droppedCount: Int get() = queue.droppedCount.get()
    val uncertainCount: Int get() = queue.uncertainCount.get()
    val isClosed: Boolean get() = finished.count == 0L

    fun threadReference(): Thread = thread

    fun start() {
        synchronized(lifecycle) {
            if (launched || stopStatus != null || queue.isClosed) return
            launched = true
            try {
                thread.start()
            } catch (e: Throwable) {
                stopStatus = SessionStates.STATUS_STARTUP_FAILED
                queue.closeAdmission()
                queue.discardQueued()
                finishFile()
                throw e
            }
        }
        try {
            initialized.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun loop() {
        var batch: List<LogRecord> = emptyList()
        try {
            onStart()
            initialized.countDown()
            while (true) {
                val first = queue.poll(POLL_MILLIS)
                if (first == null) {
                    // A poll timeout can race a producer accepted just before
                    // closeAdmission(); the queue is final only when it is also empty.
                    if (queue.isClosed && queue.size == 0) break
                    continue
                }
                val collected = ArrayList<LogRecord>(config.batchMaxRecords)
                collected.add(first)
                queue.drainUpTo(config.batchMaxRecords - collected.size)?.let { collected.addAll(it) }
                batch = collected
                val dayDropsBefore = writer.recordsDroppedByDayLimit.get()
                when (writer.write(batch)) {
                    BatchWriteOutcome.PROCESSED -> queue.markProcessed(batch.size)
                    BatchWriteOutcome.UNAVAILABLE -> queue.markFailed(batch.size)
                    BatchWriteOutcome.UNCERTAIN -> {
                        val dayDrops = writer.recordsDroppedByDayLimit.get() - dayDropsBefore
                        queue.markUncertain(batch.size - dayDrops)
                        queue.markProcessed(dayDrops)
                    }
                }
                batch = emptyList()
                recordSinkState()
            }
        } catch (e: Throwable) {
            lastError.set(e)
            // A single consumer owns all in-flight credit: settle whatever is still held
            // so a batch-construction failure cannot leave permanent orphaned credit.
            queue.markUncertain(queue.inFlightRecords)
        } finally {
            initialized.countDown()
            queue.closeAdmission()
            queue.discardQueued()
            try {
                onStop(stopStatus ?: SessionStates.STATUS_UNEXPECTED_EXIT)
            } catch (e: Throwable) {
                lastError.set(e)
            } finally {
                finishFile()
            }
        }
    }

    private fun recordSinkState() {
        if (writer.unavailable) {
            lastError.compareAndSet(null, IllegalStateException("file sink unavailable"))
        }
    }

    // Processing completion can include drops or uncertainty; this is not a persistence barrier.
    fun flush(timeoutMillis: Long = config.flushTimeoutMillis): Boolean =
        queue.awaitDrained(timeoutMillis)

    fun stopDraining(flushTimeoutMillis: Long): Boolean = close(flushTimeoutMillis)

    fun close(flushTimeoutMillis: Long, status: String = SessionStates.STATUS_CLEAN): Boolean {
        val timeoutMillis = flushTimeoutMillis.coerceAtLeast(0)
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val startedAt = System.nanoTime()
        val unstarted = synchronized(lifecycle) {
            if (stopStatus == null) stopStatus = status
            queue.closeAdmission()
            !launched
        }
        if (unstarted) {
            queue.discardQueued()
            finishFile()
        }
        val completed = try {
            Thread.currentThread() !== thread &&
                finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            queue.discardQueued()
            return false
        }

        // The completion latch is released during final cleanup, just before the
        // worker thread returns. Join as well so close() does not report success
        // while afterClose or the worker's final instructions are still running.
        val remainingNanos = timeoutNanos - (System.nanoTime() - startedAt)
        if (remainingNanos > 0L && Thread.currentThread() !== thread) {
            val joinMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
            val joinNanos = (remainingNanos - TimeUnit.MILLISECONDS.toNanos(joinMillis)).toInt()
            try {
                thread.join(joinMillis, joinNanos)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !thread.isAlive
    }

    fun closeFile() {
        synchronized(lifecycle) {
            check(!launched || isClosed) { "worker still owns the file" }
            if (stopStatus == null) stopStatus = SessionStates.STATUS_CLEAN
            queue.closeAdmission()
        }
        queue.discardQueued()
        finishFile()
    }

    private fun finishFile() {
        if (fileClosed.compareAndSet(false, true)) {
            try {
                writer.close()
            } finally {
                initialized.countDown()
                finished.countDown()
            }
            try {
                afterClose()
            } catch (e: Throwable) {
                lastError.compareAndSet(null, e)
            }
        }
    }

    fun lastError(): Throwable? = lastError.get()

    companion object {
        private const val POLL_MILLIS = 50L
    }
}
