package xtr.keymapper.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single background worker thread draining a bounded [LoggingQueue] into a
 * [SessionLogWriter] (the persistent file sink).
 *
 * Ownership:
 * - exactly one daemon thread `xtlogger-<roleTag>` per pipeline, created by
 *   [start] and deterministically joined by [close]; no leaked threads.
 * - the worker catches every `Throwable` from the sink; failures neither
 *   propagate to producers nor kill the thread ([lastError] keeps the most
 *   recent failure for tests and diagnostics).
 * - [flush] is bounded by the queue's exact drain condition
 *   ([LoggingQueue.awaitDrained]): it waits until every offered record is
 *   written or dropped, never indefinitely.
 */
class AsyncLogWorker(
    val queue: LoggingQueue,
    private val config: LoggingConfig,
    val writer: SessionLogWriter,
    roleTag: String,
) {
    private val running = AtomicBoolean(false)
    private val lastError = AtomicReference<Throwable?>(null)
    private val joined = AtomicBoolean(false)
    private val fileClosed = AtomicBoolean(false)
    private val thread = Thread({ loop() }, "xtlogger-$roleTag").apply { isDaemon = true }

    /** Current unread backlog; surfaced for tests. */
    val pendingRecords: Int get() = queue.size

    /** Records dropped by the queue's overflow policy this pipeline's lifetime. */
    val droppedCount: Int get() = queue.droppedCount.get()

    /** The worker's own thread; exposed for tests and diagnostics. */
    fun threadReference(): Thread = thread

    fun start() {
        if (running.compareAndSet(false, true)) thread.start()
    }

    private fun loop() {
        while (running.get()) {
            try {
                val first = queue.poll(POLL_MILLIS)
                if (first == null) continue
                val batch = ArrayList<LogRecord>(config.batchMaxRecords)
                batch.add(first)
                queue.drainUpTo(config.batchMaxRecords - batch.size)?.let { batch.addAll(it) }
                writer.write(batch)
                queue.markProcessed(batch.size)
                recordSinkState()
            } catch (e: Throwable) {
                // Keep serving: a failing sink must not kill the worker. Records
                // in the failed batch are closed out below to unblock flushes.
                queue.markProcessed(queue.drainUpTo(Int.MAX_VALUE)?.size ?: 0)
                lastError.set(e)
            }
        }
    }

    private fun recordSinkState() {
        if (writer.unavailable) {
            lastError.compareAndSet(null, IllegalStateException("file sink unavailable"))
        }
    }

    /**
     * Bounded flush: waits until all offered records are written or dropped.
     * Returns false only when timing out; never waits indefinitely.
     */
    fun flush(timeoutMillis: Long = config.flushTimeoutMillis): Boolean =
        queue.awaitDrained(timeoutMillis)

    /**
     * Bounded drain, then stops the consumer thread. Does NOT close the file
     * sink: the pipeline still needs it for the session END marker (written on
     * the caller thread without racing batches anymore).
     */
    fun stopDraining(flushTimeoutMillis: Long) {
        if (!joined.compareAndSet(false, true)) return
        flush(flushTimeoutMillis)
        running.set(false)
        try {
            // join is bounded: the worker polls every POLL_MILLIS and exits fast.
            thread.join(JOIN_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Closes the persistent file sink. */
    fun closeFile() {
        if (fileClosed.compareAndSet(false, true)) writer.close()
    }

    /** Convenience for plain worker users: drain-stop then close. */
    fun close(flushTimeoutMillis: Long) {
        stopDraining(flushTimeoutMillis)
        closeFile()
    }

    fun lastError(): Throwable? = lastError.get()

    companion object {
        /** Worker poll interval for an idle queue. */
        private const val POLL_MILLIS = 50L
        private const val JOIN_MILLIS = 2_000L
    }
}
