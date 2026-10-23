package xtr.keymapper.log

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fixed-capacity FIFO queue with a documented drop-oldest overflow policy and an
 * exact bounded-drain primitive for flushes.
 *
 * - capacity is fixed at construction; it never grows (no unbounded memory use on
 *   saturation, ever).
 * - when a record arrives on a full queue, the oldest queued record is evicted and
 *   the drop is counted in [droppedCount]; reporting happens once per session in
 *   the pipeline, preventing an unbounded warning stream.
 * - [pendingWrites] counts records queued-or-being-written; it is decremented the
 *   moment a record is either written or evicted, so [awaitDrained] is exact and
 *   drop-safe (a dropped record can never stall a flush).
 *
 * Lock scope is intentionally small (offer + drain), so latency-sensitive
 * producers never block on disk I/O.
 */
class LoggingQueue(private val capacity: Int) {

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val drained = lock.newCondition()
    private val deque = ArrayDeque<LogRecord>(capacity)

    /** Number of records dropped because of overflow since queue creation. */
    val droppedCount = AtomicInteger(0)

    /** Records queued or in-flight to the sink (0 when nothing is outstanding). */
    val pendingWrites = AtomicInteger(0)

    /**
     * Called by the consumer after a batch finished writing so a subsequent
     * [awaitDrained] sees exactly `0` when everything offered is processed.
     */
    fun markProcessed(count: Int) = lock.withLock {
        val left = pendingWrites.addAndGet(-count)
        if (left <= 0) {
            pendingWrites.set(0)
            drained.signalAll()
        }
    }

    /** Records currently queued. */
    val size: Int get() = lock.withLock { deque.size }

    /** Adds a record; never blocks. On a full queue the oldest record is dropped. */
    fun offer(record: LogRecord) = lock.withLock {
        if (deque.size >= capacity) {
            val evicted = deque.removeFirst()
            droppedCount.incrementAndGet()
            if (pendingWrites.decrementAndGet() == 0) drained.signalAll()
        }
        deque.addLast(record)
        pendingWrites.incrementAndGet()
        notEmpty.signalAll()
    }

    /**
     * Drains up to [maxRecords] records without waiting; null when nothing is
     * currently queued.
     */
    fun drainUpTo(maxRecords: Int): List<LogRecord>? = lock.withLock {
        if (deque.isEmpty()) null
        else {
            val out = ArrayList<LogRecord>(minOf(maxRecords, deque.size))
            while (out.size < maxRecords && deque.isNotEmpty()) out.add(deque.removeFirst())
            out
        }
    }

    /**
     * Takes one record, waiting up to [timeoutMillis]. Returns null when no
     * record arrived in time.
     */
    fun poll(timeoutMillis: Long): LogRecord? = lock.withLock {
        if (deque.isEmpty()) {
            notEmpty.awaitNanos(timeoutMillis * 1_000_000L)
        }
        if (deque.isEmpty()) null else deque.removeFirst()
    }

    /**
     * Waits until every record offered so far was written or dropped, bounded by
     * [timeoutMillis]. Returns false only on the timeout; never waits forever.
     */
    fun awaitDrained(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        lock.withLock {
            while (pendingWrites.get() > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    drained.awaitNanos(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }
}
