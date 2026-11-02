package xtr.keymapper.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class XtLoggerFacadeRegressionTest {
    private val clock = MutableClock.at("2026-10-22T09:00:00Z")

    private class FacadeStore : LogStore {
        val oldWriteEntered = CountDownLatch(1)
        val releaseOldWrite = CountDownLatch(1)
        val opens = AtomicInteger(0)
        val lines = CopyOnWriteArrayList<String>()

        val startCount: Int get() = lines.count { it.contains(SessionMarkers.START) }
        val endCount: Int get() = lines.count { it.contains(SessionMarkers.END) }

        override fun listFiles(): List<LogFileInfo> = emptyList()
        override fun openAppendWriter(name: String): LogFileWriter {
            opens.incrementAndGet()
            return object : LogFileWriter {
                override val writtenBytes: Long get() = 0L

                override fun write(chunk: ByteArray) {
                    val text = chunk.toString(Charsets.UTF_8)
                    lines.add(text)
                    if (text.contains("probe=old-session")) {
                        oldWriteEntered.countDown()
                        releaseOldWrite.await()
                    }
                }

                override fun close() {}
            }
        }

        override fun readTail(name: String, maxLines: Int) = emptyList<String>()
        override fun delete(name: String) = false
    }

    private fun pipelineFor(store: LogStore, flushMillis: Long = 100): LoggingPipeline {
        val config = LoggingConfig(
            queueCapacity = 16, flushTimeoutMillis = flushMillis, batchMaxRecords = 1,
        )
        val pipeline = LoggingPipeline(
            Role.APP, "facade-old", store, config, clock, clock.zoneId, clock.instant(), { 0 },
        )
        pipeline.start()
        return pipeline
    }

    private fun stateField(name: String): Field =
        XtLogger::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun facadeMethod(name: String, vararg types: Class<*>): Method =
        XtLogger::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }

    private fun install(owner: LoggingPipeline?, role: Role? = null) {
        stateField("pipeline").set(XtLogger, owner)
        stateField("initializationRole").set(XtLogger, role)
    }

    private fun rebuild(store: LogStore) {
        facadeMethod("rebuildAppPipeline", LogStore::class.java).invoke(XtLogger, store)
    }

    private fun awaitTrue(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    @After
    fun tearDown() {
        runCatching {
            (stateField("pipeline").get(XtLogger) as? LoggingPipeline)?.close("Teardown")
        }
        for (name in listOf("pipeline", "initializationRole", "retiringOwner", "deferredStart")) {
            runCatching { stateField(name).set(XtLogger, null) }
        }
        runCatching { stateField("fatalEventRecorded").set(XtLogger, false) }
        runCatching { stateField("roleConflictReported").set(XtLogger, false) }
    }

    @Test
    fun `the facade defers a replacement while the previous owner still holds the file`() {
        val store = FacadeStore()
        val old = pipelineFor(store)
        install(old, Role.APP)
        old.info("Facade", "probe=old-session")
        assertTrue(store.oldWriteEntered.await(2, TimeUnit.SECONDS))
        try {
            assertEquals(1, store.startCount)
            repeat(2) { rebuild(store) }
            assertEquals("no competing session while the old owner still writes", 1, store.startCount)
            assertEquals("the retiring owner has not finished its END marker", 0, store.endCount)
        } finally {
            store.releaseOldWrite.countDown()
        }
        assertTrue(awaitTrue(2_000) { old.isClosed })
        assertEquals(1, store.endCount)
        rebuild(store)
        assertEquals("exactly one new session after the old owner retires", 2, store.startCount)
        (stateField("pipeline").get(XtLogger) as LoggingPipeline?)?.close("Facade teardown")
        install(null, null)
    }

    @Test
    fun `endsession also defers the successor until a timed-out owner retires`() {
        val store = FacadeStore()
        val old = pipelineFor(store)
        install(old, Role.APP)
        old.info("Facade", "probe=old-session")
        assertTrue(store.oldWriteEntered.await(2, TimeUnit.SECONDS))
        try {
            XtLogger.endSession("Facade stopped")
            assertNull("the facade forgets the owner it stopped", stateField("pipeline").get(XtLogger))
            rebuild(store)
            assertEquals("deferred while the stopped owner still writes", 1, store.startCount)
        } finally {
            store.releaseOldWrite.countDown()
        }
        assertTrue(awaitTrue(2_000) { old.isClosed })
        assertEquals(1, store.endCount)
        rebuild(store)
        assertEquals(2, store.startCount)
        (stateField("pipeline").get(XtLogger) as LoggingPipeline?)?.close("Facade teardown")
        install(null, null)
    }

    @Test
    fun `ordinary initialization cannot bypass a retiring file owner`() {
        val store = FacadeStore()
        val old = pipelineFor(store)
        install(old, Role.APP)
        old.info("Facade", "probe=old-session")
        assertTrue(store.oldWriteEntered.await(2, TimeUnit.SECONDS))
        try {
            XtLogger.endSession("Facade stopped")
            repeat(2) {
                facadeMethod("initInternal", Role::class.java, LogStore::class.java)
                    .invoke(XtLogger, Role.SHELL, store)
            }
            assertFalse("no successor while the previous write is blocked", XtLogger.isInitialized)
            assertNull(XtLogger.currentRole)
            assertEquals(1, store.opens.get())
            assertEquals(1, store.startCount)
            assertEquals(0, store.endCount)
            assertEquals("the blocked write keeps its credit", 1, old.inFlightRecords)
            assertFalse(old.isClosed)
        } finally {
            store.releaseOldWrite.countDown()
        }
        assertTrue(awaitTrue(2_000) { old.isClosed })
        assertEquals(0, old.inFlightRecords)
        assertEquals(1, store.endCount)
        facadeMethod("initInternal", Role::class.java, LogStore::class.java)
            .invoke(XtLogger, Role.SHELL, store)
        assertEquals(Role.SHELL, XtLogger.currentRole)
        assertEquals(2, store.startCount)
    }

    @Test
    fun `a deferred init restarts automatically when the blocking owner retires`() {
        val store = FacadeStore()
        facadeMethod("internalsStart", Role::class.java, LogStore::class.java)
            .invoke(XtLogger, Role.APP, store)
        val old = stateField("pipeline").get(XtLogger) as LoggingPipeline
        old.info("Facade", "probe=old-session")
        assertTrue(store.oldWriteEntered.await(2, TimeUnit.SECONDS))
        try {
            XtLogger.endSession("Facade stopped")
            facadeMethod("initInternal", Role::class.java, LogStore::class.java)
                .invoke(XtLogger, Role.SHELL, store)
            assertFalse(XtLogger.isInitialized)
            assertEquals("still only the old session", 1, store.startCount)
        } finally {
            store.releaseOldWrite.countDown()
        }
        assertTrue(awaitTrue(2_000) { old.isClosed })
        assertEquals(1, store.endCount)
        assertTrue(
            "file logging recovers automatically without a later resume or init call",
            awaitTrue(2_000) { XtLogger.isInitialized },
        )
        assertTrue(awaitTrue(2_000) { store.startCount == 2 })
        assertEquals("the deferred init parameters restart", Role.SHELL, XtLogger.currentRole)
    }

    @Test
    fun `endsession closes exactly the captured owner and is idempotent`() {
        val store = FacadeStore()
        val owner = pipelineFor(store)
        install(owner, Role.APP)
        XtLogger.endSession("Facade stopped")
        assertTrue(owner.isClosed)
        assertFalse(XtLogger.isInitialized)
        assertNull("the stopped owner leaves no role pointing at a dead session", XtLogger.currentRole)
        assertEquals(1, store.endCount)
        val opens = store.opens.get()
        XtLogger.endSession("Facade stopped")
        assertEquals(1, store.endCount)
        assertEquals(opens, store.opens.get())
        install(null, null)
    }

    @Test
    fun `a second init request does not replace the existing owner`() {
        val store = FacadeStore()
        val owner = pipelineFor(store)
        install(owner, Role.SHELL)
        facadeMethod("initInternal", Role::class.java, LogStore::class.java)
            .invoke(XtLogger, Role.SHIZUKU, store)
        assertEquals(owner, stateField("pipeline").get(XtLogger))
        assertEquals(1, store.startCount)
        install(null, null)
    }

    @Test
    fun `fatal flushes the same owner whose fatal record was emitted`() {
        val store = FacadeStore()
        val owner = pipelineFor(store)
        install(owner, Role.APP)
        XtLogger.fatal("Facade", "probe=fatal-record")
        assertTrue("the fatal record reached the active owner", store.lines.any { it.contains("probe=fatal-record") })
        assertTrue(awaitTrue(2_000) { store.lines.size >= 2 })
        install(null, null)
    }

    @Test
    fun `fatal before init records the flag without touching storage`() {
        runCatching { stateField("fatalEventRecorded").set(XtLogger, false) }
        XtLogger.fatal("Facade", "no-pipeline-fatal")
        assertTrue(XtLogger.hasFatalEvent)
        assertEquals(0, FacadeStore().opens.get())
    }

    @Test
    fun `the facade keeps no process wide mutable duration base`() {
        assertThrows(NoSuchFieldException::class.java) {
            XtLogger::class.java.getDeclaredField("monotonicallyStartedNanos")
        }
    }
}
