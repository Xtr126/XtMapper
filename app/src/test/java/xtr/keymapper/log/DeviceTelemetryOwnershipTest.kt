package xtr.keymapper.log

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DeviceTelemetryOwnershipTest {
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val collectors = mutableListOf<DeviceTelemetry>()
    private val emptyProc = object : ProcSource {
        override fun read(path: String): String? = null
        override fun list(path: String): List<String> = emptyList()
    }

    private fun collector(emit: (String) -> Unit = {}, scheduler: TelemetryScheduler): DeviceTelemetry =
        DeviceTelemetry({ 0 }, scheduler, { null }, emptyProc, { null }, 34, emit).also { collectors.add(it) }

    private fun launch(block: () -> Unit) = thread(isDaemon = true) {
        try {
            block()
        } catch (t: Throwable) {
            failures.add(t)
        }
    }

    private fun join(worker: Thread) {
        worker.join(2_000)
        assertFalse("test worker did not finish", worker.isAlive)
        assertTrue("worker failures: $failures", failures.isEmpty())
    }

    private fun setRole(role: Role?) {
        XtLogger::class.java.getDeclaredField("initializationRole").apply {
            isAccessible = true
            set(XtLogger, role)
        }
    }

    @After
    fun tearDown() {
        DeviceTelemetry.stop()
        collectors.forEach { runCatching { it.stop() } }
        setRole(null)
    }

    @Test
    fun `app and uninitialized public starts never access platform services`() {
        val calls = AtomicInteger()
        val context = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? {
                calls.incrementAndGet()
                return null
            }
        }
        setRole(null)
        DeviceTelemetry.start(context)
        setRole(Role.APP)
        DeviceTelemetry.start(context)
        assertEquals("APP must be gated before platform construction", 0, calls.get())
    }

    @Test
    fun `platform construction failure cannot escape public start`() {
        setRole(Role.SHELL)
        val context = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = throw SecurityException("denied")
        }
        DeviceTelemetry.start(context)
        DeviceTelemetry.stop()
    }

    @Test
    fun `factory failure leaves the owner available for a later start`() {
        DeviceTelemetry.start(Role.SHELL, create = { throw IllegalStateException("construction failed") })
        val schedules = AtomicInteger()
        val telemetry = collector { _, _, _ ->
            schedules.incrementAndGet()
            TelemetryCancellation {}
        }
        DeviceTelemetry.start(Role.SHELL, create = { telemetry })
        assertEquals(1, schedules.get())
    }

    @Test
    fun `duplicate start does not construct unused platform adapters`() {
        val telemetry = collector { _, _, _ -> TelemetryCancellation {} }
        DeviceTelemetry.start(Role.SHELL, create = { telemetry })
        val duplicates = AtomicInteger()
        DeviceTelemetry.start(Role.SHELL, create = {
            duplicates.incrementAndGet()
            telemetry
        })
        assertEquals(0, duplicates.get())
    }

    @Test
    fun `stop between publication and startup cannot leave an orphan collector`() {
        val published = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopInvoked = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val schedules = AtomicInteger()
        val cancels = AtomicInteger()
        val records = CopyOnWriteArrayList<String>()
        var task: Runnable? = null
        val telemetry = collector(
            scheduler = { _, _, runnable ->
                task = runnable
                schedules.incrementAndGet()
                TelemetryCancellation { cancels.incrementAndGet() }
            },
            emit = records::add,
        )
        val starting = launch {
            DeviceTelemetry.start(Role.SHELL, create = { telemetry }, beforeStart = {
                published.countDown()
                assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
            })
        }
        assertTrue(published.await(2, TimeUnit.SECONDS))
        val stopping = launch {
            stopInvoked.countDown()
            DeviceTelemetry.stop()
            stopFinished.countDown()
        }
        var stoppedBeforeStart = false
        try {
            assertTrue(stopInvoked.await(2, TimeUnit.SECONDS))
            stoppedBeforeStart = stopFinished.await(100, TimeUnit.MILLISECONDS)
        } finally {
            releaseStart.countDown()
            join(starting)
            join(stopping)
        }
        assertFalse("stop returned before the published collector could start", stoppedBeforeStart)
        assertEquals(1, schedules.get())
        assertEquals("the published collector was actually cancelled", 1, cancels.get())
        val before = records.size
        task!!.run()
        assertEquals(before, records.size)
    }

    @Test
    fun `replacement cannot overlap an owner whose stop is still cancelling`() {
        val cancelling = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val old = collector { _, _, _ ->
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            TelemetryCancellation {
                cancelling.countDown()
                assertTrue(releaseStop.await(2, TimeUnit.SECONDS))
                active.decrementAndGet()
            }
        }
        val next = collector { _, _, _ ->
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            replacementStarted.countDown()
            TelemetryCancellation { active.decrementAndGet() }
        }
        DeviceTelemetry.start(Role.SHELL, create = { old })
        val stopping = launch { DeviceTelemetry.stop() }
        assertTrue(cancelling.await(2, TimeUnit.SECONDS))
        val starting = launch { DeviceTelemetry.start(Role.SHIZUKU, create = { next }) }
        var overlapped = false
        try {
            overlapped = replacementStarted.await(100, TimeUnit.MILLISECONDS)
        } finally {
            releaseStop.countDown()
            join(stopping)
            join(starting)
        }
        assertFalse("the next collector started while the old cancellation was blocked", overlapped)
        assertEquals("single-owner collection", 1, maximum.get())
        assertEquals(1, active.get())
        DeviceTelemetry.stop()
        assertEquals(0, active.get())
    }

    @Test
    fun `schedule failure resets collector state and allows a clean retry`() {
        var reject = true
        val schedules = AtomicInteger()
        val telemetry = collector { _, _, _ ->
            if (reject) throw IllegalStateException("schedule failed")
            schedules.incrementAndGet()
            TelemetryCancellation {}
        }
        DeviceTelemetry.start(Role.SHELL, create = { telemetry })
        reject = false
        DeviceTelemetry.start(Role.SHELL, create = { telemetry })
        assertEquals(1, schedules.get())
    }
}
