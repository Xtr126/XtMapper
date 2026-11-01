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

    val droppedCount = AtomicInteger(0)

    val pendingWrites = AtomicInteger(0)

    fun markProcessed(count: Int) = lock.withLock {
        val left = pendingWrites.addAndGet(-count)
        if (left <= 0) {
            pendingWrites.set(0)
            drained.signalAll()
        }
    }

    fun markFailed(count: Int) = lock.withLock {
        if (count > 0) {
            droppedCount.addAndGet(count)
            val left = pendingWrites.addAndGet(-count)
            if (left <= 0) {
                pendingWrites.set(0)
                drained.signalAll()
            }
        }
    }

    val size: Int get() = lock.withLock { deque.size }

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

    fun drainUpTo(maxRecords: Int): List<LogRecord>? = lock.withLock {
        if (deque.isEmpty()) null
        else {
            val out = ArrayList<LogRecord>(minOf(maxRecords, deque.size))
            while (out.size < maxRecords && deque.isNotEmpty()) out.add(deque.removeFirst())
            out
        }
    }

    fun poll(timeoutMillis: Long): LogRecord? = lock.withLock {
        if (deque.isEmpty()) {
            notEmpty.awaitNanos(timeoutMillis * 1_000_000L)
        }
        if (deque.isEmpty()) null else deque.removeFirst()
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
