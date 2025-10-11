package xtr.keymapper.server.windows

import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import xtr.keymapper.server.Input

class TouchpadInputInjector {
    private var lastTouchDown: Long = 0
    private val input = Input(Display.DEFAULT_DISPLAY)

    fun inject(
        prevContacts: List<TouchpadContact>,
        currContacts: List<TouchpadContact>
    ) {
        val motionEvent = obtainMotionEvent(prevContacts, currContacts)
        input.injectInputEvent(motionEvent)
    }

    fun obtainMotionEvent(
        prevContacts: List<TouchpadContact>,
        currContacts: List<TouchpadContact>,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN
    ): MotionEvent? {
        // Filter only "active" contacts (those with tip == true)
        val prevActive = prevContacts.filter { it.tip }
        val currActive = currContacts.filter { it.tip }

        val prevIds = prevActive.map { it.contactId }.toSet()
        val currIds = currActive.map { it.contactId }.toSet()

        val added = currIds - prevIds
        val removed = prevIds - currIds

        val now = SystemClock.uptimeMillis()

        // Determine pointer set to use for event
        // (Use previous active if current is empty, because pointerCount cannot be 0)
        val effectiveActive = if (currActive.isEmpty() && prevActive.isNotEmpty()) prevActive else currActive
        val pointerCount = effectiveActive.size

        // Build PointerProperties[] and PointerCoords[] from effective active contacts
        val pointerProperties = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().apply {
                id = effectiveActive[i].contactId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val pointerCoords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().apply {
                x = effectiveActive[i].x.toFloat()
                y = effectiveActive[i].y.toFloat()
                pressure = 1f
                size = 1f
            }
        }

// --- Decide correct action ---
        val action = when {
            prevActive.isEmpty() && currActive.isNotEmpty() -> {
                println("Branch: ACTION_DOWN")
                println("prevActive is empty: ${prevActive.isEmpty()}, currActive is not empty: ${currActive.isNotEmpty()}")
                MotionEvent.ACTION_DOWN
            }

            added.isNotEmpty() -> {
                val addedId = added.first()
                val index = currActive.indexOfFirst { it.contactId == addedId }
                println("Branch: ACTION_POINTER_DOWN")
                println("Added contacts: $added")
                println("Chosen addedId: $addedId")
                println("Index of added contact in currActive: $index")
                val actionValue = MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                println("Computed ACTION_POINTER_DOWN value: $actionValue")
                actionValue
            }

            removed.isNotEmpty() -> {
                val removedId = removed.first()
                val index = prevActive.indexOfLast { it.contactId == removedId }
                println("Branch: ACTION_POINTER_UP or ACTION_UP")
                println("Removed contacts: $removed")
                println("Chosen removedId: $removedId")
                println("Index of removed contact in prevActive: $index")

                if (currActive.isEmpty()) {
                    println("currActive is empty → ACTION_UP")
                    MotionEvent.ACTION_UP
                } else {
                    println("currActive is not empty → ACTION_POINTER_UP")
                    val actionValue = MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    println("Computed ACTION_POINTER_UP value: $actionValue")
                    actionValue
                }
            }

            prevActive.isNotEmpty() && currActive.isNotEmpty() -> {
                println("Branch: ACTION_MOVE")
                println("Both prevActive and currActive are not empty")
                MotionEvent.ACTION_MOVE
            }

            else -> {
                println("Branch: No active contacts → returning null")
                println("prevActive: $prevActive, currActive: $currActive, added: $added, removed: $removed")
                return null // No active contacts and nothing to lift
            }
        }

        return MotionEvent.obtain(
            lastTouchDown,
            now,
            action,
            pointerCount,
            pointerProperties,
            pointerCoords,
            0, // metaState
            0, // buttonState
            1f, // xPrecision
            1f, // yPrecision
            0,
            0, // edgeFlags
            source,
            0  // flags
        )
    }

}