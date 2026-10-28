package xtr.keymapper.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single background worker thread draining a bounded [LoggingQueue] into a
 * [SessionLogWriter] (the persistent file sink).
 *
 * One daemon thread `xtlogger-<roleTag>` per pipeline, created by [start] and
 * deterministically joined by [stopDraining]; sink failures never propagate to
 * producers nor kill the thread ([lastError] keeps the most recent one).
 */
class AsyncLogWorker(
    val queue: LoggingQueue,
    private val config: LoggingConfig,
    val writer: SessionLogWriter,
    private val roleTag: String,
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
            // Declared outside the try so the catch can still account a failure
            // thrown while the batch was being written.
            var batch: List<LogRecord> = emptyList()
            try {
                val first = queue.poll(POLL_MILLIS)
                if (first == null) continue
                val collected = ArrayList<LogRecord>(config.batchMaxRecords)
                collected.add(first)
                queue.drainUpTo(config.batchMaxRecords - collected.size)?.let { collected.addAll(it) }
                batch = collected
                writer.write(batch)
                if (writer.unavailable) {
                    // The fail-soft writer absorbed the failure (no exception
                    // escaped): the batch was NOT persisted. Accounting it as
                    // written would hide the loss; it is dropped and counted.
                    queue.markFailed(batch.size)
                } else {
                    queue.markProcessed(batch.size)
                }
                recordSinkState()
            } catch (e: Throwable) {
                // The write threw: the in-flight batch above plus everything
                // still queued are closed out as one dropped event. Draining and
                // dropping the backlog is deliberate — the sink rejected work
                // right now and a dead sink would only spin — but neither the
                // failed batch nor a drain may be counted as successful writes,
                // and pendingWrites must reach 0 so flush() still completes.
                val discarded = queue.drainUpTo(Int.MAX_VALUE) ?: emptyList()
                queue.markFailed(batch.size + discarded.size)
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
     * Bounded: waits until every offered record is written or dropped, false
     * only on timeout.
     */
    fun flush(timeoutMillis: Long = config.flushTimeoutMillis): Boolean =
        queue.awaitDrained(timeoutMillis)

    /**
     * Bounded drain and stop. Returns true only when the queue drained and the
     * worker thread terminated. A false result means the caller must not touch
     * the sink concurrently; an in-flight write may still own its file lock.
     */
    fun stopDraining(flushTimeoutMillis: Long): Boolean {
        if (!joined.compareAndSet(false, true)) return !thread.isAlive
        val drained = flush(flushTimeoutMillis)
        running.set(false)
        try {
            // join is bounded: the worker polls every POLL_MILLIS and exits fast
            // unless it is blocked inside the file sink.
            thread.join(JOIN_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return drained && !thread.isAlive
    }

    fun closeFile() {
        if (fileClosed.compareAndSet(false, true)) writer.close()
    }

    /** Convenience for plain worker users: close only after the consumer is gone. */
    fun close(flushTimeoutMillis: Long) {
        if (stopDraining(flushTimeoutMillis)) {
            closeFile()
            return
        }

        // The worker may still hold a file lock while blocked in write(). Never
        // close that sink on this caller thread; discard queued records and defer
        // cleanup until the worker has really exited.
        val abandoned = queue.drainUpTo(Int.MAX_VALUE)?.size ?: 0
        queue.markFailed(abandoned)
        Thread({
            var interrupted = false
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            closeFile()
            if (interrupted) Thread.currentThread().interrupt()
        }, "xtlogger-close-$roleTag").apply {
            isDaemon = true
            start()
        }
    }

    fun lastError(): Throwable? = lastError.get()

    companion object {
        /** Worker poll interval for an idle queue. */
        private const val POLL_MILLIS = 50L
        private const val JOIN_MILLIS = 2_000L
    }
}
