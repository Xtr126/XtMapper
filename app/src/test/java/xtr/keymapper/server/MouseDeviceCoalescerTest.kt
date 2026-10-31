package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseDeviceCoalescerTest {

    /** Fake Handler: posts are queued and only drained when the test explicitly runs them. */
    private class FakeDelayedHandler {
        val queue = ArrayDeque<() -> Unit>()
        var postCount = 0
            private set

        fun post(task: () -> Unit) {
            postCount++
            queue.addLast(task)
        }

        fun runNext() {
            queue.removeFirst().invoke()
        }

        fun runAll() {
            while (queue.isNotEmpty()) runNext()
        }
    }

    @Test
    fun `interleaved requests schedule one task and process only the latest device`() {
        val handler = FakeDelayedHandler()
        val processed = mutableListOf<String>()
        val coalescer = MouseDeviceCoalescer(
            schedule = { task -> handler.post(task) },
            process = { device -> processed.add(device) },
        )

        coalescer.request("A")
        coalescer.request("B")
        coalescer.request("A")
        coalescer.request("B")

        // The Handler queue must not grow: four interleaved requests -> one pending task.
        assertEquals(1, handler.queue.size)

        handler.runAll()

        // Only the latest selection is processed; the obsolete A path is never opened.
        assertEquals(listOf("B"), processed)
    }

    @Test
    fun `a newer selection after the drain completes is still discovered`() {
        val handler = FakeDelayedHandler()
        val processed = mutableListOf<String>()
        val coalescer = MouseDeviceCoalescer(
            schedule = { task -> handler.post(task) },
            process = { device -> processed.add(device) },
        )

        coalescer.request("A")
        handler.runAll()
        assertEquals(listOf("A"), processed)

        // After the drain finished, a new request schedules a fresh task.
        coalescer.request("C")
        assertEquals(2, handler.postCount)
        handler.runAll()
        assertEquals(listOf("A", "C"), processed)
    }

    @Test
    fun `a selection requested mid-open is not lost and does not flood the queue`() {
        val handler = FakeDelayedHandler()
        val processed = mutableListOf<String>()
        var coalescerRef: MouseDeviceCoalescer? = null
        val coalescer = MouseDeviceCoalescer(
            schedule = { task -> handler.post(task) },
            process = { device ->
                processed.add(device)
                // Simulate a newer device arriving from the reader thread while this one is
                // being opened on the Handler.
                if (device == "A") coalescerRef!!.request("B")
            },
        )
        coalescerRef = coalescer

        coalescer.request("A")
        handler.runAll()

        // B was requested while A was being processed; a continuation picks it up (not lost).
        assertEquals(listOf("A", "B"), processed)
        // One continuation for the pending selection: at most one task is ever queued, one
        // per pending selection rather than one per raw event.
        assertEquals(2, handler.postCount)
    }

    @Test
    fun `queued shutdown task runs before an endless discovery stream finishes`() {
        val handler = FakeDelayedHandler()
        val processed = mutableListOf<String>()
        var coalescerRef: MouseDeviceCoalescer? = null
        val coalescer = MouseDeviceCoalescer(
            schedule = { task -> handler.post(task) },
            process = { device ->
                processed.add(device)
                // Endless stream: every processed selection yields another, as a moving
                // mouse's EV_REL lines do while another device interleaves. A drain that
                // looped inline would never return to the Handler.
                coalescerRef!!.request("next-$device")
            },
        )
        coalescerRef = coalescer

        coalescer.request("A")
        var sentinelRan = false
        handler.post { sentinelRan = true } // e.g. stopServer posted behind the drain
        assertEquals(2, handler.queue.size)

        handler.runNext() // first drain: processes A, queues one continuation
        assertFalse(sentinelRan)
        // Exactly one continuation behind the sentinel: the stream is endless, so per-event
        // posting or an inline loop would have grown the queue / never returned here.
        assertEquals(2, handler.queue.size)

        handler.runNext() // sentinel runs next, while the discovery stream is still live
        assertTrue(sentinelRan)
        assertEquals(listOf("A"), processed)
    }
}
