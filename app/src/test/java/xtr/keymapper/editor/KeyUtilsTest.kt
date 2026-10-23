package xtr.keymapper.editor

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KeyUtilsTest {

    @Test
    fun testAlphabeticKeys() {
        assertEquals("A", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_A))
        assertEquals("Z", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_Z))
        assertEquals("W", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_W))
    }

    @Test
    fun testNumericKeys() {
        assertEquals("0", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_0))
        assertEquals("1", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_1))
        assertEquals("9", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_9))
    }

    @Test
    fun testModifierKeys() {
        assertEquals("LEFTSHIFT", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals("RIGHTSHIFT", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals("LEFTCTRL", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals("RIGHTCTRL", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals("LEFTALT", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals("RIGHTALT", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_ALT_RIGHT))
    }

    @Test
    fun testSpecialAndNavigationKeys() {
        assertEquals("SPACE", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_SPACE))
        assertEquals("ENTER", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_ENTER))
        assertEquals("TAB", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_TAB))
        assertEquals("ESC", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("BACKSPACE", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_DEL))
        assertEquals("UP", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("DOWN", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("LEFT", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("RIGHT", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun testSymbolsAndFunctionKeys() {
        assertEquals("F1", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_F1))
        assertEquals("F12", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_F12))
        assertEquals("GRAVE", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_GRAVE))
        assertEquals("MINUS", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_MINUS))
        assertEquals("EQUAL", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_EQUALS))
        assertEquals("SLASH", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_SLASH))
    }

    @Test
    fun testNumpadKeys() {
        assertEquals("KP0", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals("KPENTER", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals("KPPLUS", KeyUtils.resolveKeyName(KeyEvent.KEYCODE_NUMPAD_ADD))
    }
}
