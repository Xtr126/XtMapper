package xtr.keymapper.shiftkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.keymap.element.Key
import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.TouchJitter
import xtr.keymapper.server.event.KeyEventHandler
import xtr.keymapper.server.event.KeyEventHandler.KeyEvent
import xtr.keymapper.server.event.MouseEventHandler
import xtr.keymapper.server.pid.PidProvider

class ShiftKeyHandlerTest {

    private data class RecordedEvent(
        val x: Float,
        val y: Float,
        val action: Int,
        val pointerId: Int
    )

    private class MockInputInterface : IInputInterface {
        val events = mutableListOf<RecordedEvent>()

        override fun injectEvent(x: Float, y: Float, action: Int, pointerId: Int) {
            events.add(RecordedEvent(x, y, action, pointerId))
        }

        override fun injectHoverEvent(x: Float, y: Float, pointerId: Int) {}
        override fun injectRightClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {}
        override fun injectMiddleClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {}
        override fun injectScroll(x: Float, y: Float, value: Float) {}
        override fun pauseResumeKeymap() {}
        override val keymapConfig: KeymapConfig get() = throw UnsupportedOperationException()
        override val keyEventHandler: KeyEventHandler get() = throw UnsupportedOperationException()
        override val mouseEventHandler: MouseEventHandler get() = throw UnsupportedOperationException()
        override val keymapProfile: KeymapProfile get() = throw UnsupportedOperationException()
        override val callback: IRemoteServiceCallback get() = throw UnsupportedOperationException()
        override fun moveCursorX(x: Int) {}
        override fun moveCursorY(y: Int) {}
        override fun hideCursor() {}
        override fun showCursor() {}
        override fun toggleKeyboardBlock() {}
        override val isKeyboardBlocked: Boolean get() = false
    }

    private fun createShiftKey(
        key1Code: String = "A",
        x1: Float = 100f,
        y1: Float = 200f,
        key2Code: String = "B",
        x2: Float = 500f,
        y2: Float = 600f
    ): ShiftKey {
        val shiftKey = ShiftKey()
        shiftKey.key1.code = key1Code
        shiftKey.key1.x = x1
        shiftKey.key1.y = y1
        shiftKey.key2.code = key2Code
        shiftKey.key2.x = x2
        shiftKey.key2.y = y2
        return shiftKey
    }

    private fun keyEvent(code: String, action: Int): KeyEvent {
        return KeyEvent().apply {
            this.code = code
            this.action = action
        }
    }

    @Test
    fun testSingleKey1PressAndRelease() {
        val shiftKey = createShiftKey()
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        // Press A -> DOWN at (100, 200)
        handler.handleEvent(keyEvent("KEY_A", InputService.DOWN), mockService, pidProvider)
        assertEquals(1, mockService.events.size)
        assertEquals(RecordedEvent(100f, 200f, InputService.DOWN, 0), mockService.events[0])

        // Release A -> UP at (100, 200)
        handler.handleEvent(keyEvent("KEY_A", InputService.UP), mockService, pidProvider)
        assertEquals(2, mockService.events.size)
        assertEquals(RecordedEvent(100f, 200f, InputService.UP, 0), mockService.events[1])
    }

    @Test
    fun testSingleKey2PressAndRelease() {
        val shiftKey = createShiftKey()
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        // Press B -> DOWN at (500, 600)
        handler.handleEvent(keyEvent("KEY_B", InputService.DOWN), mockService, pidProvider)
        assertEquals(1, mockService.events.size)
        assertEquals(RecordedEvent(500f, 600f, InputService.DOWN, 0), mockService.events[0])

        // Release B -> UP at (500, 600)
        handler.handleEvent(keyEvent("KEY_B", InputService.UP), mockService, pidProvider)
        assertEquals(2, mockService.events.size)
        assertEquals(RecordedEvent(500f, 600f, InputService.UP, 0), mockService.events[1])
    }

    @Test
    fun testHoldKey1ThenPressKey2ShiftsToKey2() {
        val shiftKey = createShiftKey()
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        // 1. Press and hold Key 1 (A)
        handler.handleEvent(keyEvent("KEY_A", InputService.DOWN), mockService, pidProvider)
        assertEquals(RecordedEvent(100f, 200f, InputService.DOWN, 0), mockService.events[0])

        // 2. While holding Key 1, press Key 2 (B) -> Shifting via MOVE to (500, 600)
        handler.handleEvent(keyEvent("KEY_B", InputService.DOWN), mockService, pidProvider)
        assertEquals(2, mockService.events.size)
        assertEquals(RecordedEvent(500f, 600f, InputService.MOVE, 0), mockService.events[1])

        // 3. Release Key 1 while Key 2 is still held down -> remains held at Key 2
        handler.handleEvent(keyEvent("KEY_A", InputService.UP), mockService, pidProvider)
        assertEquals(2, mockService.events.size)

        // 4. Release Key 2 -> UP at (500, 600)
        handler.handleEvent(keyEvent("KEY_B", InputService.UP), mockService, pidProvider)
        assertEquals(3, mockService.events.size)
        assertEquals(RecordedEvent(500f, 600f, InputService.UP, 0), mockService.events[2])
    }

    @Test
    fun testHoldKey1ThenPressKey2ThenReleaseKey2ShiftsBackToKey1() {
        val shiftKey = createShiftKey()
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        // 1. Hold A
        handler.handleEvent(keyEvent("KEY_A", InputService.DOWN), mockService, pidProvider)
        // 2. Press B -> shifts to B
        handler.handleEvent(keyEvent("KEY_B", InputService.DOWN), mockService, pidProvider)
        assertEquals(RecordedEvent(500f, 600f, InputService.MOVE, 0), mockService.events[1])

        // 3. Release B while A is still held -> shifts back to A
        handler.handleEvent(keyEvent("KEY_B", InputService.UP), mockService, pidProvider)
        assertEquals(3, mockService.events.size)
        assertEquals(RecordedEvent(100f, 200f, InputService.MOVE, 0), mockService.events[2])

        // 4. Release A -> UP at A
        handler.handleEvent(keyEvent("KEY_A", InputService.UP), mockService, pidProvider)
        assertEquals(4, mockService.events.size)
        assertEquals(RecordedEvent(100f, 200f, InputService.UP, 0), mockService.events[3])
    }

    @Test
    fun testHoldKey2ThenPressKey1ShiftsToKey1() {
        val shiftKey = createShiftKey()
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        // 1. Hold B -> DOWN at B
        handler.handleEvent(keyEvent("KEY_B", InputService.DOWN), mockService, pidProvider)
        assertEquals(RecordedEvent(500f, 600f, InputService.DOWN, 0), mockService.events[0])

        // 2. Press A -> shifts to A
        handler.handleEvent(keyEvent("KEY_A", InputService.DOWN), mockService, pidProvider)
        assertEquals(RecordedEvent(100f, 200f, InputService.MOVE, 0), mockService.events[1])

        // 3. Release A while B is still held -> shifts back to B
        handler.handleEvent(keyEvent("KEY_A", InputService.UP), mockService, pidProvider)
        assertEquals(RecordedEvent(500f, 600f, InputService.MOVE, 0), mockService.events[2])

        // 4. Release B -> UP at B
        handler.handleEvent(keyEvent("KEY_B", InputService.UP), mockService, pidProvider)
        assertEquals(RecordedEvent(500f, 600f, InputService.UP, 0), mockService.events[3])
    }

    @Test
    fun testShiftKeyConfigParsingAndSerialization() {
        val configLine = "SHIFT_KEY Q 150.0 250.0 E 750.0 850.0"
        val profile = KeymapProfiles.parse(setOf(configLine))

        assertEquals(1, profile.shiftKeys.size)
        val shiftKey = profile.shiftKeys[0]
        assertEquals("Q", shiftKey.key1.code)
        assertEquals(150.0f, shiftKey.key1.x)
        assertEquals(250.0f, shiftKey.key1.y)
        assertEquals("E", shiftKey.key2.code)
        assertEquals(750.0f, shiftKey.key2.x)
        assertEquals(850.0f, shiftKey.key2.y)

        assertEquals("SHIFT_KEY Q 150.0 250.0 E 750.0 850.0", shiftKey.data)
    }

    @Test
    fun testShiftKeyConfigParsingAndSerializationWithDuration() {
        val configLine = "SHIFT_KEY Q 150.0 250.0 E 750.0 850.0 150"
        val profile = KeymapProfiles.parse(setOf(configLine))

        assertEquals(1, profile.shiftKeys.size)
        val shiftKey = profile.shiftKeys[0]
        assertEquals("Q", shiftKey.key1.code)
        assertEquals(150.0f, shiftKey.key1.x)
        assertEquals(250.0f, shiftKey.key1.y)
        assertEquals("E", shiftKey.key2.code)
        assertEquals(750.0f, shiftKey.key2.x)
        assertEquals(850.0f, shiftKey.key2.y)
        assertEquals(150, shiftKey.durationMs)

        assertEquals("SHIFT_KEY Q 150.0 250.0 E 750.0 850.0 150", shiftKey.data)
    }

    @Test
    fun testInterpolatedSmoothTransition() {
        val shiftKey = createShiftKey(x1 = 100f, y1 = 200f, x2 = 500f, y2 = 600f)
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        val scheduledTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val scheduler: (Long, () -> Unit) -> Unit = { delay, task ->
            scheduledTasks.add(Pair(delay, task))
        }

        // 1. Press Key 1 (A) -> DOWN at (100, 200)
        handler.handleEvent(
            keyEvent("KEY_A", InputService.DOWN),
            mockService,
            pidProvider,
            defaultDurationMs = 100,
            scheduler = scheduler
        )
        assertEquals(1, mockService.events.size)
        assertEquals(RecordedEvent(100f, 200f, InputService.DOWN, 0), mockService.events[0])

        // 2. Press Key 2 (B) -> initiates smooth transition over 100ms
        handler.handleEvent(
            keyEvent("KEY_B", InputService.DOWN),
            mockService,
            pidProvider,
            defaultDurationMs = 100,
            scheduler = scheduler
        )
        // Multiple intermediate steps should be scheduled
        assertTrue(scheduledTasks.size >= 8)

        // Execute scheduled steps in chronological order
        scheduledTasks.sortBy { it.first }
        var lastX = 100f
        var lastY = 200f
        for ((_, task) in scheduledTasks) {
            task()
            val latest = mockService.events.last()
            assertEquals(InputService.MOVE, latest.action)
            assertTrue("X should be monotonically increasing", latest.x >= lastX)
            assertTrue("Y should be monotonically increasing", latest.y >= lastY)
            lastX = latest.x
            lastY = latest.y
        }

        // Final step reaches exact target (500, 600)
        assertEquals(500f, lastX, 0.01f)
        assertEquals(600f, lastY, 0.01f)
    }

    @Test
    fun testMidTransitionReleaseCancelsAndLiftsAtCurrentPosition() {
        val shiftKey = createShiftKey(x1 = 100f, y1 = 200f, x2 = 500f, y2 = 600f)
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        val scheduledTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val scheduler: (Long, () -> Unit) -> Unit = { delay, task ->
            scheduledTasks.add(Pair(delay, task))
        }

        // 1. Press Key 1
        handler.handleEvent(keyEvent("KEY_A", InputService.DOWN), mockService, pidProvider, scheduler = scheduler)
        // 2. Press Key 2 -> transition started
        handler.handleEvent(keyEvent("KEY_B", InputService.DOWN), mockService, pidProvider, defaultDurationMs = 100, scheduler = scheduler)

        // Run only first 3 steps (partial transition)
        scheduledTasks.sortBy { it.first }
        scheduledTasks[0].second.invoke()
        scheduledTasks[1].second.invoke()
        scheduledTasks[2].second.invoke()

        val midX = handler.currentX
        val midY = handler.currentY
        assertTrue(midX > 100f && midX < 500f)
        assertTrue(midY > 200f && midY < 600f)

        // Release Key 1 then Key 2 (both released)
        handler.handleEvent(keyEvent("KEY_A", InputService.UP), mockService, pidProvider, scheduler = scheduler)
        handler.handleEvent(keyEvent("KEY_B", InputService.UP), mockService, pidProvider, scheduler = scheduler)

        val upEvent = mockService.events.last()
        assertEquals(InputService.UP, upEvent.action)
        assertEquals(midX, upEvent.x, 0.01f)
        assertEquals(midY, upEvent.y, 0.01f)

        // Stale runnables from the original transition should now be ignored
        val eventCountBefore = mockService.events.size
        scheduledTasks.last().second.invoke()
        assertEquals("Stale step should not inject event", eventCountBefore, mockService.events.size)
    }

    @Test
    fun testMidTransitionReversalStartsFromCurrentPosition() {
        val shiftKey = createShiftKey(x1 = 100f, y1 = 200f, x2 = 500f, y2 = 600f)
        val handler = ShiftKeyHandler(shiftKey)
        val mockService = MockInputInterface()
        val pidProvider = PidProvider()

        val scheduledTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val scheduler: (Long, () -> Unit) -> Unit = { delay, task ->
            scheduledTasks.add(Pair(delay, task))
        }

        // 1. Press A
        handler.handleEvent(keyEvent("KEY_A", InputService.DOWN), mockService, pidProvider, scheduler = scheduler)
        // 2. Press B -> moving to B
        handler.handleEvent(keyEvent("KEY_B", InputService.DOWN), mockService, pidProvider, defaultDurationMs = 100, scheduler = scheduler)

        // Execute first 3 steps towards B
        scheduledTasks[0].second.invoke()
        scheduledTasks[1].second.invoke()
        scheduledTasks[2].second.invoke()

        val turnaroundX = handler.currentX
        val turnaroundY = handler.currentY
        assertTrue(turnaroundX > 100f && turnaroundX < 500f)

        // 3. Release B while A is still held -> reverse back towards A(100, 200)
        val reversalTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val reversalScheduler: (Long, () -> Unit) -> Unit = { delay, task ->
            reversalTasks.add(Pair(delay, task))
        }
        handler.handleEvent(keyEvent("KEY_B", InputService.UP), mockService, pidProvider, defaultDurationMs = 100, scheduler = reversalScheduler)

        assertTrue(reversalTasks.isNotEmpty())
        reversalTasks.sortBy { it.first }
        // Run first step of reversal -> should move towards A from turnaround position
        reversalTasks[0].second.invoke()
        val reversedLatest = mockService.events.last()
        assertTrue(reversedLatest.x < turnaroundX)
        assertTrue(reversedLatest.y < turnaroundY)
    }

    @Test
    fun testTouchJitterAppliedToShiftKeyEvents() {
        val shiftKey = createShiftKey(x1 = 100f, y1 = 200f, x2 = 500f, y2 = 600f)
        val handler = ShiftKeyHandler(shiftKey)
        val pidProvider = PidProvider()

        // Create an input interface that passes coordinates through TouchJitter
        val touchJitter = TouchJitter(isEnabled = true, spread = 25f)
        val capturedEvents = mutableListOf<RecordedEvent>()
        val jitteredService = object : IInputInterface {
            override fun injectEvent(x: Float, y: Float, action: Int, pointerId: Int) {
                val wrapped = touchJitter.wrapCoordinates(x, y, action, pointerId)
                capturedEvents.add(RecordedEvent(wrapped.x, wrapped.y, action, pointerId))
            }

            override fun injectHoverEvent(x: Float, y: Float, pointerId: Int) {}
            override fun injectRightClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {}
            override fun injectMiddleClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {}
            override fun injectScroll(x: Float, y: Float, value: Float) {}
            override fun pauseResumeKeymap() {}
            override val keymapConfig: KeymapConfig get() = throw UnsupportedOperationException()
            override val keyEventHandler: KeyEventHandler get() = throw UnsupportedOperationException()
            override val mouseEventHandler: MouseEventHandler get() = throw UnsupportedOperationException()
            override val keymapProfile: KeymapProfile get() = throw UnsupportedOperationException()
            override val callback: IRemoteServiceCallback get() = throw UnsupportedOperationException()
            override fun moveCursorX(x: Int) {}
            override fun moveCursorY(y: Int) {}
            override fun hideCursor() {}
            override fun showCursor() {}
            override fun toggleKeyboardBlock() {}
            override val isKeyboardBlocked: Boolean get() = false
        }

        // 1. Press Key 1 -> DOWN with jitter offset
        handler.handleEvent(keyEvent("KEY_A", InputService.DOWN), jitteredService, pidProvider)
        assertEquals(1, capturedEvents.size)
        val downEvent = capturedEvents[0]
        assertEquals(InputService.DOWN, downEvent.action)
        val dx = downEvent.x - 100f
        val dy = downEvent.y - 200f
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        assertTrue("Jitter offset should be within spread", dist <= 25.01f)

        // 2. Press Key 2 -> instantaneous shift (MOVE)
        handler.handleEvent(keyEvent("KEY_B", InputService.DOWN), jitteredService, pidProvider, defaultDurationMs = 0)
        val moveEvent = capturedEvents[1]
        assertEquals(InputService.MOVE, moveEvent.action)
        // Consistent stroke jitter offset must be maintained
        assertEquals(500f + dx, moveEvent.x, 0.01f)
        assertEquals(600f + dy, moveEvent.y, 0.01f)

        // 3. Release both keys -> UP with same jitter offset
        handler.handleEvent(keyEvent("KEY_A", InputService.UP), jitteredService, pidProvider)
        handler.handleEvent(keyEvent("KEY_B", InputService.UP), jitteredService, pidProvider)
        val upEvent = capturedEvents.last()
        assertEquals(InputService.UP, upEvent.action)
        assertEquals(500f + dx, upEvent.x, 0.01f)
        assertEquals(600f + dy, upEvent.y, 0.01f)
    }
}
