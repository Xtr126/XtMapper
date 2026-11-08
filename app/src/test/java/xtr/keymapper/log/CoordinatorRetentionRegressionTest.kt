package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CoordinatorRetentionRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private class AcquisitionPause {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun now(): Long {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "acquisition pause was not released" }
            }
            return System.nanoTime()
        }
    }

    @Test
    fun `retention rechecks each candidate after acquiring the deletion gate`() {
        val dir = tmp.newFolder()
        val first = "xtmapper-2026-08-01.log"
        val second = "xtmapper-2026-08-02.log"
        val coordinator = CoordinatorStore(dir)
        listOf(first, second, "xtmapper-2026-10-23.log").forEach {
            coordinator.append(it, listOf(FrameCodec.encode(1, "seed", "kept")))
        }
        val pause = AcquisitionPause()
        val clock = MutableClock.at("2026-10-23T09:00:00Z")
        val deleting = CoordinatorStore(dir, clock = pause::now)
        val retentionStore = CoordinatorSessionWriter(deleting, dir, null, clock, clock.zoneId, "retention").store
        val pipeline = TestPipelineFactory(FileSystemLogStore(dir), clock).start(manualStart = false)
        val active = AtomicReference<Set<String>>(emptySet())
        val executor = Executors.newSingleThreadExecutor()
        try {
            pause.armed.set(true)
            val deletion = executor.submit {
                pipeline.applyRetention(retentionStore, LoggingConfig(), LocalDate.of(2026, 10, 23), active::get)
            }
            assertTrue("candidate selection precedes the paused acquisition", pause.entered.await(3, TimeUnit.SECONDS))
            active.set(setOf(first))
            pause.release.countDown()
            deletion.get(5, TimeUnit.SECONDS)
            assertTrue("the candidate became ineligible before the lock was acquired", File(dir, first).isFile)
            assertFalse("the other eligible candidate is still deleted", File(dir, second).exists())
        } finally {
            pause.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            pipeline.close()
        }
    }

    @Test
    fun `retention rechecks the count budget after another deletion wins`() {
        val dir = tmp.newFolder()
        val first = "xtmapper-2026-10-20.log"
        val second = "xtmapper-2026-10-21.log"
        val coordinator = CoordinatorStore(dir)
        listOf(first, second, "xtmapper-2026-10-23.log").forEach {
            coordinator.append(it, listOf(FrameCodec.encode(1, "seed", "kept")))
        }
        val pause = AcquisitionPause()
        val clock = MutableClock.at("2026-10-23T09:00:00Z")
        val store = CoordinatorSessionWriter(CoordinatorStore(dir, clock = pause::now), dir, null, clock, clock.zoneId, "retention").store
        val pipeline = TestPipelineFactory(FileSystemLogStore(dir), clock).start(manualStart = false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            pause.armed.set(true)
            val deletion = executor.submit {
                pipeline.applyRetention(store, LoggingConfig(maxFiles = 2), LocalDate.of(2026, 10, 23))
            }
            assertTrue(pause.entered.await(3, TimeUnit.SECONDS))
            assertTrue(coordinator.delete(second))
            pause.release.countDown()
            deletion.get(5, TimeUnit.SECONDS)
            assertTrue("the remaining files fit the count budget before acquisition", File(dir, first).isFile)
        } finally {
            pause.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            pipeline.close()
        }
    }

    @Test
    fun `retention winning after old-day selection cannot resurrect that day`() {
        val dir = tmp.newFolder()
        val oldName = "xtmapper-2026-10-01.log"
        val currentName = "xtmapper-2026-10-03.log"
        val retention = CoordinatorStore(dir)
        retention.append(oldName, listOf(FrameCodec.encode(1, "seed", "kept")))
        val pause = AcquisitionPause()
        val coordinator = CoordinatorStore(dir, clock = pause::now)
        val clock = MutableClock.at("2026-10-03T12:00:00Z")
        val writer = CoordinatorSessionWriter(coordinator, dir, null, clock, clock.zoneId, "late-race")
        writer.configure(SessionState.DISPOSITION_FIRST, SessionMetadata(), clock.instant())
        writer.writeSessionStartMarker()
        val delayed = LogRecord.now(
            java.time.Instant.parse("2026-10-01T09:00:00Z"), Level.INFO, "t", "delayed after deletion",
            null, 42, Role.APP.tag, writer.sessionId,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            pause.armed.set(true)
            val writing = executor.submit<BatchWriteOutcome> { writer.write(listOf(delayed)) }
            assertTrue("old-day selection precedes the paused acquisition", pause.entered.await(3, TimeUnit.SECONDS))
            assertTrue("retention deletes the selected older day while append has no lock", retention.delete(oldName))
            pause.release.countDown()
            assertEquals(BatchWriteOutcome.PROCESSED, writing.get(5, TimeUnit.SECONDS))
            assertFalse("append must not recreate the deleted day", File(dir, oldName).exists())
            val payloads = retention.readRecovery(currentName).frames.map { it.payload }
            val late = payloads.indexOfFirst { it.lineSequence().any { line -> line == SessionMarkers.LATE } }
            val record = payloads.indexOfFirst { it.contains("message=\"delayed after deletion\"") }
            assertTrue("fallback includes the intended day", late >= 0 && payloads[late].contains("Intended Day: 2026-10-01"))
            assertTrue("the delayed record follows its LATE marker in the anchor day", record > late)
        } finally {
            pause.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            writer.close()
        }
    }
}
