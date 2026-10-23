package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LoggingQueueTest {

    private fun record(n: Int, message: String = "m$n"): LogRecord = LogRecord(
        time = Instant.EPOCH, level = Level.INFO, tag = "t", message = message,
        pid = 1, role = "test", threadName = "x", threadId = 1,
    )

    @Test
    fun `capacity is fixed and offer never grows the queue`() {
        val queue = LoggingQueue(3)
        repeat(10) { queue.offer(record(it)) }
        assertEquals(3, queue.size)
        assertEquals(3, queue.pendingWrites.get())
        // Drop-oldest policy: the oldest records were evicted.
        assertEquals(7, queue.droppedCount.get())
        assertEquals(listOf("m7", "m8", "m9"), queue.drainUpTo(3)!!.map { it.message })
    }

    @Test
    fun `queue drains in fifo order`() {
        val queue = LoggingQueue(4)
        repeat(4) { queue.offer(record(it)) }
        val drained = queue.drainUpTo(10)!!.map { it.message }
        assertEquals(listOf("m0", "m1", "m2", "m3"), drained)
        assertEquals(0, queue.size)
    }

    @Test
    fun `drainUpTo returns null when idle`() {
        val queue = LoggingQueue(2)
        assertNull(queue.drainUpTo(4))
    }

    @Test
    fun `pendingWrites reaches zero after markProcessed`() {
        val queue = LoggingQueue(4)
        repeat(3) { queue.offer(record(it)) }
        assertEquals(3, queue.pendingWrites.get())
        queue.markProcessed(3)
        assertEquals(0, queue.pendingWrites.get())
    }

    @Test
    fun `awaitDrained completes immediately on an empty queue`() {
        val queue = LoggingQueue(2)
        assertTrue(queue.awaitDrained(50))
    }

    @Test
    fun `awaitDrained completes when records processed, and times out when not`() {
        val queue = LoggingQueue(4)
        repeat(2) { queue.offer(record(it)) }
        // Not yet processed → bounded timeout returns false.
        assertFalse(queue.awaitDrained(40))
        queue.markProcessed(2)
        assertTrue(queue.awaitDrained(200))
    }

    @Test
    fun `drop-oldest records never stall awaits even while processed later`() {
        val queue = LoggingQueue(1)
        repeat(3) { queue.offer(record(it)) }
        assertEquals(2, queue.droppedCount.get())
        assertEquals(1, queue.pendingWrites.get())
        queue.markProcessed(1)
        assertTrue(queue.awaitDrained(100))
    }

    @Test
    fun `poll respects the timeout while empty`() {
        val queue = LoggingQueue(2)
        val start = System.nanoTime()
        assertNull(queue.poll(30))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("poll waited ~30ms", elapsedMs in 20..5_000)
    }

    @Test
    fun `poll wakes for a new record`() {
        val queue = LoggingQueue(2)
        val thread = Thread {
            try { Thread.sleep(50) } catch (e: InterruptedException) {}
            queue.offer(record(1))
        }
        thread.start()
        val got = queue.poll(2_000)
        thread.join()
        assertEquals("m1", got?.message)
    }

    @Test
    fun `single record offer does not block while drained concurrently`() {
        val queue = LoggingQueue(1)
        val processedCount = AtomicInteger(0)
        val done = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val consumer = Thread {
            while (!stop.get()) {
                val first = queue.poll(25)
                if (first != null) {
                    queue.drainUpTo(8)?.let { queue.markProcessed(it.size) }
                    queue.markProcessed(1)
                    processedCount.incrementAndGet()
                }
            }
            done.countDown()
        }
        consumer.isDaemon = true
        consumer.start()
        repeat(500) { queue.offer(record(it)) }
        stop.set(true)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(processedCount.get() > 0)
    }
}
