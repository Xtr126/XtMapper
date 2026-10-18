package xtr.keymapper.editor

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText

fun onKeyboardKeyPress(view: View, keyCode: Int, event: KeyEvent): Boolean {
    val key = event.getDisplayLabel().toString()
    if (key.matches("[a-zA-Z0-9]+".toRegex())) (view as EditText).setText(key)
    else (view as EditText).getText().clear()
    return true
}

fun onMouseClick(v: View?, event: MotionEvent?): Boolean {
    // Handle middle mouse button
    if (event!!.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
        if (event.getAction() == MotionEvent.ACTION_BUTTON_PRESS) {
            if (event.getActionButton() == MotionEvent.BUTTON_TERTIARY) {
                // Middle mouse button was pressed
                (v as EditText).setText("MMB")
                return true
            } else if (event.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                // Right mouse button was pressed
                (v as EditText).setText("RMB")
                return true
            }
        }
    }
    return false // Event not handled
}

fun keyCodePrefix(e: EditText?): String {
    return "KEY_" + e!!.getText()
}
