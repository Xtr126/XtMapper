package xtr.keymapper.server.windows

import android.view.Display
import android.view.MotionEvent
import xtr.keymapper.server.Input
import xtr.keymapper.server.InputService

class TouchpadInputInjector {
    var verbose: Boolean = false
    private val input = Input(Display.DEFAULT_DISPLAY)

    fun inject(
        prevContacts: List<TouchpadContact>,
        currContacts: List<TouchpadContact>
    ) {
        for (contact in currContacts) {
            with(contact) {


                val action = if (prevContacts.any { it.contactId == contactId }) {
                                val prevContact = prevContacts.first { it.contactId == contactId }

                                if (!prevContact.tip && tip) InputService.DOWN
                                else if (tip) InputService.MOVE
                                else InputService.UP
                            } else {
                                InputService.DOWN
                            }

                if (verbose) println("injectEvent $x, $y, ${
                    when(action) { 
                        InputService.MOVE -> "MOVE"
                        InputService.UP -> "UP"
                        InputService.DOWN -> "DOWN"
                        else -> ""
                    }
                }, $contactId")

                injectEvent(x.toFloat(), y.toFloat(), action, contact.contactId)
            }
        }
    }



    fun injectEvent(x: Float, y: Float, action: Int, pointerId: Int) {
        when (action) {
            InputService.UP -> input.injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f, x, y)
            InputService.DOWN -> input.injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f, x, y)
            InputService.MOVE -> input.injectTouch(MotionEvent.ACTION_MOVE, pointerId, 0.0f, x, y)
        }
    }
}