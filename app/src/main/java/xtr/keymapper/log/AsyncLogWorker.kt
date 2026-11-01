package xtr.keymapper.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    val pendingRecords: Int get() = queue.size

    val droppedCount: Int get() = queue.droppedCount.get()

    fun threadReference(): Thread = thread

    fun start() {
        if (running.compareAndSet(false, true)) thread.start()
    }

    private fun loop() {
        while (running.get()) {
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
                    queue.markFailed(batch.size)
                } else {
                    queue.markProcessed(batch.size)
                }
                recordSinkState()
            } catch (e: Throwable) {
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

    fun flush(timeoutMillis: Long = config.flushTimeoutMillis): Boolean =
        queue.awaitDrained(timeoutMillis)

    fun stopDraining(flushTimeoutMillis: Long): Boolean {
        if (!joined.compareAndSet(false, true)) return !thread.isAlive
        val drained = flush(flushTimeoutMillis)
        running.set(false)
        try {
            thread.join(JOIN_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return drained && !thread.isAlive
    }

    fun closeFile() {
        if (fileClosed.compareAndSet(false, true)) writer.close()
    }

    fun close(flushTimeoutMillis: Long) {
        if (stopDraining(flushTimeoutMillis)) {
            closeFile()
            return
        }

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
        private const val POLL_MILLIS = 50L
        private const val JOIN_MILLIS = 2_000L
    }
}
