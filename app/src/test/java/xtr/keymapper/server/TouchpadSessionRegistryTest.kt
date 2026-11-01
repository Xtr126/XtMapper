package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xtr.keymapper.keymap.KeymapConfig

/**
 * Decision-table tests for the touchpad session registry. Owners are plain
 * [Any] tokens: the registry only needs reference identity, and InputService
 * cannot be constructed in host unit tests.
 */
class TouchpadSessionRegistryTest {

    private class FakeNative : TouchpadNativeOps<Any> {
        val startCalls = mutableListOf<Pair<Any, Int>>()
        val stopCalls = mutableListOf<Any>()
        var startCode: Int = TouchpadStartCodes.STARTED
        val stopScript = ArrayDeque<Int>()
        var stopCode: Int = TouchpadStopCodes.COMPLETED

        override fun start(owner: Any, mode: Int): Int {
            startCalls.add(owner to mode)
            return startCode
        }

        override fun stop(owner: Any): Int {
            stopCalls.add(owner)
            return stopScript.removeFirstOrNull() ?: stopCode
        }
    }

    private val ownerA = Any()
    private val ownerB = Any()
    private val direct = KeymapConfig.TOUCHPAD_DIRECT
    private val relative = KeymapConfig.TOUCHPAD_RELATIVE

    @Test
    fun `start with no active session calls native once and records the owner`() {
        val native = FakeNative()
        val registry = TouchpadSessionRegistry(native)

        assertEquals(TouchpadStartOutcome.Started, registry.start(ownerA, direct))
        assertEquals(listOf(ownerA to direct), native.startCalls)

        // Teardown calls native stop for the exact owner.
        assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerA))
        assertEquals(listOf(ownerA), native.stopCalls)
    }

    @Test
    fun `same owner and mode restarts idempotently without a native call`() {
        val native = FakeNative()
        val registry = TouchpadSessionRegistry(native)

        registry.start(ownerA, direct)
        assertEquals(TouchpadStartOutcome.Started, registry.start(ownerA, direct))
        assertEquals(1, native.startCalls.size)
    }

    @Test
    fun `same owner in a different mode is blocked - direct and relative never overlap`() {
        val native = FakeNative()
        val registry = TouchpadSessionRegistry(native)

        registry.start(ownerA, direct)
        assertEquals(TouchpadStartOutcome.Blocked, registry.start(ownerA, relative))
        assertEquals(1, native.startCalls.size)
    }

    @Test
    fun `a different owner cannot start while another session is active`() {
        val native = FakeNative()
        val registry = TouchpadSessionRegistry(native)

        registry.start(ownerA, direct)
        assertEquals(TouchpadStartOutcome.Blocked, registry.start(ownerB, direct))
        assertEquals(TouchpadStartOutcome.Blocked, registry.start(ownerB, relative))
        assertEquals(1, native.startCalls.size)

        // A stale stop from the inactive owner must not tear down ownerA's run.
        assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerB))
        assertTrue(native.stopCalls.isEmpty())
        assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerA))
        assertEquals(listOf(ownerA), native.stopCalls)
    }

    @Test
    fun `disabled mode never reaches native`() {
        val native = FakeNative()
        val registry = TouchpadSessionRegistry(native)

        assertEquals(TouchpadStartOutcome.Blocked, registry.start(ownerA, KeymapConfig.TOUCHPAD_DISABLED))
        assertTrue(native.startCalls.isEmpty())
    }

    @Test
    fun `no device maps to NoDevice and leaves no active owner`() {
        val native = FakeNative().apply { startCode = TouchpadStartCodes.NO_DEVICE }
        val registry = TouchpadSessionRegistry(native)

        assertEquals(TouchpadStartOutcome.NoDevice, registry.start(ownerA, direct))
        // Not active, so stopping it is a no-op without a native call.
        assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerA))
        assertTrue(native.stopCalls.isEmpty())
        // The next session can start immediately.
        native.startCode = TouchpadStartCodes.STARTED
        assertEquals(TouchpadStartOutcome.Started, registry.start(ownerB, direct))
    }

    @Test
    fun `native start failure codes surface as Failed with the code`() {
        for (code in listOf(
            TouchpadStartCodes.SCAN_ERROR,
            TouchpadStartCodes.ACCESS_ERROR,
            TouchpadStartCodes.ACQUISITION_FAILED,
            TouchpadStartCodes.OWNER_REF_FAILED,
            TouchpadStartCodes.THREAD_FAILED,
            TouchpadStartCodes.CANCELLED,
            TouchpadStartCodes.INTERNAL_ERROR,
        )) {
            val native = FakeNative().apply { startCode = code }
            val registry = TouchpadSessionRegistry(native)
            assertEquals(TouchpadStartOutcome.Failed(code), registry.start(ownerA, direct))
            assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerA))
            assertTrue(native.stopCalls.isEmpty())
        }
    }

    @Test
    fun `native BUSY surfaces as Blocked rather than double-starting`() {
        val native = FakeNative().apply { startCode = TouchpadStartCodes.BUSY }
        val registry = TouchpadSessionRegistry(native)

        assertEquals(TouchpadStartOutcome.Blocked, registry.start(ownerA, direct))
        assertTrue(native.stopCalls.isEmpty())
    }

    @Test
    fun `incomplete start retains an obligation that blocks starts until retried`() {
        val native = FakeNative().apply { startCode = TouchpadStartCodes.START_INCOMPLETE }
        val registry = TouchpadSessionRegistry(native)

        assertEquals(TouchpadStartOutcome.StartIncomplete, registry.start(ownerA, direct))

        // Every new start first retries the obligation; while it stays
        // unresolved no session may start.
        native.stopScript.add(TouchpadStopCodes.INCOMPLETE)
        assertEquals(TouchpadStartOutcome.Blocked, registry.start(ownerB, direct))
        assertEquals(listOf(ownerA), native.stopCalls)

        native.stopScript.add(TouchpadStopCodes.INCOMPLETE)
        assertEquals(TouchpadStartOutcome.Blocked, registry.start(ownerB, relative))
        assertEquals(2, native.stopCalls.size)
        assertEquals(1, native.startCalls.size) // no new native start happened

        // Once the retry succeeds, the very same start proceeds.
        native.stopScript.add(TouchpadStopCodes.COMPLETED)
        native.startCode = TouchpadStartCodes.STARTED
        assertEquals(TouchpadStartOutcome.Started, registry.start(ownerB, direct))
        assertEquals(3, native.stopCalls.size)
        assertEquals(2, native.startCalls.size)
    }

    @Test
    fun `incomplete stop of the active owner keeps an obligation for retry`() {
        val native = FakeNative().apply { stopCode = TouchpadStopCodes.INCOMPLETE }
        val registry = TouchpadSessionRegistry(native)
        registry.start(ownerA, direct)

        assertEquals(TouchpadStopOutcome.INCOMPLETE, registry.stop(ownerA))

        // The active owner was released at the registry level but the native
        // obligation still blocks new starts until the retry succeeds; the
        // retry runs inside the next start and then proceeds.
        native.stopCode = TouchpadStopCodes.COMPLETED
        assertEquals(TouchpadStartOutcome.Started, registry.start(ownerB, direct))
        assertEquals(listOf(ownerA, ownerA), native.stopCalls)
    }

    @Test
    fun `stop of an owner with an unresolved obligation reports INCOMPLETE`() {
        val native = FakeNative().apply { startCode = TouchpadStartCodes.START_INCOMPLETE }
        val registry = TouchpadSessionRegistry(native)
        registry.start(ownerA, direct)

        // Keep the retry failing during this stop.
        native.stopScript.add(TouchpadStopCodes.INCOMPLETE)
        assertEquals(TouchpadStopOutcome.INCOMPLETE, registry.stop(ownerA))
        assertEquals(1, native.stopCalls.size)

        // Repeated stop retries and reports STOPPED once resolved (the default
        // stopCode answers the second retry).
        assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerA))
        assertEquals(2, native.stopCalls.size)
    }

    @Test
    fun `stop of an owner that was never active is a no-op`() {
        val native = FakeNative()
        val registry = TouchpadSessionRegistry(native)

        assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerA))
        assertTrue(native.stopCalls.isEmpty())
    }

    @Test
    fun `a throwing native stop keeps the obligation instead of losing it`() {
        val native = object : TouchpadNativeOps<Any> {
            var fail = true
            val stopCalls = mutableListOf<Any>()
            override fun start(owner: Any, mode: Int) = TouchpadStartCodes.STARTED
            override fun stop(owner: Any): Int {
                stopCalls.add(owner)
                if (fail) throw RuntimeException("jni boom")
                return TouchpadStopCodes.COMPLETED
            }
        }
        val registry = TouchpadSessionRegistry(native)
        registry.start(ownerA, direct)

        assertEquals(TouchpadStopOutcome.INCOMPLETE, registry.stop(ownerA))
        assertEquals(1, native.stopCalls.size)

        native.fail = false
        assertEquals(TouchpadStopOutcome.STOPPED, registry.stop(ownerA))
        assertEquals(2, native.stopCalls.size)

        // Obligation resolved: a fresh session starts without more retries.
        val before = native.stopCalls.size
        assertEquals(TouchpadStartOutcome.Started, registry.start(ownerB, direct))
        assertEquals(before, native.stopCalls.size)
    }
}
