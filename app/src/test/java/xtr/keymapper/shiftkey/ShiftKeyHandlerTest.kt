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
}
