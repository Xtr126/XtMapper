package xtr.keymapper.log

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class LoggingQueue(private val capacity: Int) {

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val drained = lock.newCondition()
    private val deque = ArrayDeque<LogRecord>(capacity)
    private var closed = false
    private var inFlight = 0

    init {
        require(capacity > 0)
    }

    val droppedCount = AtomicInteger(0)

    val pendingWrites = AtomicInteger(0)
    val uncertainCount = AtomicInteger(0)

    fun markProcessed(count: Int) = settle(count)

    fun markFailed(count: Int) = settle(count, droppedCount)

    fun markUncertain(count: Int) = settle(count, uncertainCount)

    private fun settle(count: Int, outcome: AtomicInteger? = null) = lock.withLock {
        check(count >= 0 && count <= inFlight) { "settlement exceeds in-flight records" }
        inFlight -= count
        outcome?.addAndGet(count)
        if (pendingWrites.addAndGet(-count) == 0) drained.signalAll()
    }

    val size: Int get() = lock.withLock { deque.size }
    val inFlightRecords: Int get() = lock.withLock { inFlight }
    val isClosed: Boolean get() = lock.withLock { closed }

    // True means admission, not delivery; an accepted record can still be evicted.
    fun offer(record: LogRecord): Boolean = lock.withLock {
        if (closed) return false
        if (deque.size >= capacity) {
            deque.removeFirst()
            droppedCount.incrementAndGet()
            pendingWrites.decrementAndGet()
        }
        deque.addLast(record)
        pendingWrites.incrementAndGet()
        notEmpty.signalAll()
        true
    }

    fun closeAdmission() = lock.withLock {
        closed = true
        notEmpty.signalAll()
    }

    fun discardQueued(): Int = lock.withLock {
        val count = deque.size
        deque.clear()
        droppedCount.addAndGet(count)
        if (pendingWrites.addAndGet(-count) == 0) drained.signalAll()
        count
    }

    fun drainUpTo(maxRecords: Int): List<LogRecord>? = lock.withLock {
        require(maxRecords >= 0)
        if (deque.isEmpty()) null
        else {
            val out = ArrayList<LogRecord>(minOf(maxRecords, deque.size))
            while (out.size < maxRecords && deque.isNotEmpty()) out.add(deque.removeFirst())
            inFlight += out.size
            out
        }
    }

    fun poll(timeoutMillis: Long): LogRecord? = lock.withLock {
        if (deque.isEmpty() && !closed) {
            notEmpty.awaitNanos(timeoutMillis * 1_000_000L)
        }
        if (deque.isEmpty()) null else deque.removeFirst().also { inFlight++ }
    }

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
