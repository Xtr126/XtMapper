package xtr.keymapper.editor

import android.view.KeyEvent

object KeyUtils {
    /**
     * Resolves an Android KeyEvent / keyCode into the canonical key name matching Linux evdev (without "KEY_").
     * Supports alphanumeric keys, modifiers (Shift, Ctrl, Alt), navigation, function keys, and symbols.
     */
    @JvmStatic
    fun resolveKeyName(keyCode: Int, event: KeyEvent? = null): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_UNKNOWN -> null

            // Alphanumeric
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
            }

            // Function keys
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1)
            }

            // Modifiers & special keys
            KeyEvent.KEYCODE_SPACE -> "SPACE"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "LEFTSHIFT"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "RIGHTSHIFT"
            KeyEvent.KEYCODE_CTRL_LEFT -> "LEFTCTRL"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "RIGHTCTRL"
            KeyEvent.KEYCODE_ALT_LEFT -> "LEFTALT"
            KeyEvent.KEYCODE_ALT_RIGHT -> "RIGHTALT"
            KeyEvent.KEYCODE_TAB -> "TAB"
            KeyEvent.KEYCODE_ENTER -> "ENTER"
            KeyEvent.KEYCODE_ESCAPE -> "ESC"
            KeyEvent.KEYCODE_DEL -> "BACKSPACE"
            KeyEvent.KEYCODE_FORWARD_DEL -> "DELETE"
            KeyEvent.KEYCODE_CAPS_LOCK -> "CAPSLOCK"
            KeyEvent.KEYCODE_INSERT -> "INSERT"
            KeyEvent.KEYCODE_MOVE_HOME -> "HOME"
            KeyEvent.KEYCODE_MOVE_END -> "END"
            KeyEvent.KEYCODE_PAGE_UP -> "PAGEUP"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PAGEDOWN"

            // Arrow keys
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"

            // Punctuation / symbols
            KeyEvent.KEYCODE_GRAVE -> "GRAVE"
            KeyEvent.KEYCODE_MINUS -> "MINUS"
            KeyEvent.KEYCODE_EQUALS -> "EQUAL"
            KeyEvent.KEYCODE_LEFT_BRACKET -> "LEFTBRACE"
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "RIGHTBRACE"
            KeyEvent.KEYCODE_BACKSLASH -> "BACKSLASH"
            KeyEvent.KEYCODE_SEMICOLON -> "SEMICOLON"
            KeyEvent.KEYCODE_APOSTROPHE -> "APOSTROPHE"
            KeyEvent.KEYCODE_COMMA -> "COMMA"
            KeyEvent.KEYCODE_PERIOD -> "DOT"
            KeyEvent.KEYCODE_SLASH -> "SLASH"

            // Numpad
            KeyEvent.KEYCODE_NUMPAD_0 -> "KP0"
            KeyEvent.KEYCODE_NUMPAD_1 -> "KP1"
            KeyEvent.KEYCODE_NUMPAD_2 -> "KP2"
            KeyEvent.KEYCODE_NUMPAD_3 -> "KP3"
            KeyEvent.KEYCODE_NUMPAD_4 -> "KP4"
            KeyEvent.KEYCODE_NUMPAD_5 -> "KP5"
            KeyEvent.KEYCODE_NUMPAD_6 -> "KP6"
            KeyEvent.KEYCODE_NUMPAD_7 -> "KP7"
            KeyEvent.KEYCODE_NUMPAD_8 -> "KP8"
            KeyEvent.KEYCODE_NUMPAD_9 -> "KP9"
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "KPENTER"
            KeyEvent.KEYCODE_NUMPAD_ADD -> "KPPLUS"
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "KPMINUS"
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "KPASTERISK"
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "KPSLASH"
            KeyEvent.KEYCODE_NUMPAD_DOT -> "KPDOT"

            else -> {
                val label = event?.displayLabel?.toString()?.uppercase()
                if (label != null && label.length == 1 && label.matches("[A-Z0-9]".toRegex())) {
                    label
                } else {
                    val codeStr = KeyEvent.keyCodeToString(keyCode)
                    if (codeStr.startsWith("KEYCODE_")) {
                        codeStr.removePrefix("KEYCODE_")
                    } else null
                }
            }
        }
    }
}
