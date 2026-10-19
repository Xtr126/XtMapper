package xtr.keymapper.server

import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.genymobile.scrcpy.Point
import com.genymobile.scrcpy.Pointer
import com.genymobile.scrcpy.PointersState
import xtr.keymapper.server.event.MouseEventHandler
import java.lang.reflect.Method

class Input(private val displayId: Int) {

    private val pointersState = PointersState()
    private val pointerProperties = arrayOfNulls<MotionEvent.PointerProperties>(PointersState.MAX_POINTERS)
    private val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(PointersState.MAX_POINTERS)
    private var lastTouchDown: Long = 0
    private var pointerCount = 0

    private val mScrollThread = SmoothScrollThread()

    private fun initPointers() {
        for (i in 0 until PointersState.MAX_POINTERS) {
            val props = MotionEvent.PointerProperties()
            props.toolType = MotionEvent.TOOL_TYPE_FINGER

            val coords = MotionEvent.PointerCoords()
            coords.orientation = 0f
            coords.size = 0f

            pointerProperties[i] = props
            pointerCoords[i] = coords
        }
    }

    fun noPointersDown(): Boolean {
        if (pointerCount > 0) {
            pointerCount = pointersState.update(pointerProperties, pointerCoords)
            for (i in 0 until pointerCount) {
                if (!pointersState[i].isUp) return false
            }
        }
        return true
    }

    init {
        initPointers()
    }

    fun injectTouch(actionIn: Int, pointerId: Int, pressure: Float, x: Float, y: Float) {
        var action = actionIn
        val now = SystemClock.uptimeMillis()
        val point = Point(x, y)

        val pointerIndex = pointersState.getPointerIndex(pointerId.toLong())
        if (pointerIndex == -1) {
            Log.e(RemoteService.TAG, "Too many pointers for touch event")
        }
        val pointer = pointersState[pointerIndex]
        pointer.point = point
        pointer.pressure = pressure
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_HOVER_MOVE)
            pointer.isUp = true
        else if (action == MotionEvent.ACTION_DOWN) pointer.isUp = false

        val source = InputDevice.SOURCE_TOUCHSCREEN

        pointerCount = pointersState.update(pointerProperties, pointerCoords)

        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now
            }
        } else {
            // secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP or
                        (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else if (action == MotionEvent.ACTION_DOWN) {
                action = MotionEvent.ACTION_POINTER_DOWN or
                        (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }
        val motionEvent = MotionEvent.obtain(lastTouchDown, now, action, pointerCount,
            pointerProperties, pointerCoords,
            0, 0, 1f, 1f,
            0, 0, source, 0)
        injectInputEvent(motionEvent)
    }

    internal fun injectMouseClick(pointerId: Long, x: Float, y: Float, pressed: Boolean, actionButton: Int) {
        val now = SystemClock.uptimeMillis()

        val pressure = 1.0f
        var action = if (pressed) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
        var buttons = if (pressed) actionButton else 0

        val point = Point(x, y)

        val pointerIndex = pointersState.getPointerIndex(pointerId)
        if (pointerIndex == -1) {
            Log.e(RemoteService.TAG, "Too many pointers for touch event")
        }

        val pointer = pointersState[pointerIndex]
        pointer.point = point
        pointer.pressure = pressure

        val source: Int
        if (pointerId == MouseEventHandler.pointerId.toLong()) {
            // real mouse event, or event incompatible with a finger
            pointerProperties[pointerIndex]!!.toolType = MotionEvent.TOOL_TYPE_MOUSE
            source = InputDevice.SOURCE_MOUSE
            pointer.isUp = buttons == 0
        } else {
            // POINTER_ID_GENERIC_FINGER, POINTER_ID_VIRTUAL_FINGER or real touch from device
            pointerProperties[pointerIndex]!!.toolType = MotionEvent.TOOL_TYPE_FINGER
            source = InputDevice.SOURCE_TOUCHSCREEN
            // Buttons must not be set for touch events
            buttons = 0
            pointer.isUp = action == MotionEvent.ACTION_UP
        }

        val pointerCount = pointersState.update(pointerProperties, pointerCoords)
        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now
            }
        } else {
            // secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else {
                action = MotionEvent.ACTION_POINTER_DOWN or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }

        /* If the input device is a mouse (on API >= 23):
         *   - the first button pressed must first generate ACTION_DOWN;
         *   - all button pressed (including the first one) must generate ACTION_BUTTON_PRESS;
         *   - all button released (including the last one) must generate ACTION_BUTTON_RELEASE;
         *   - the last button released must in addition generate ACTION_UP.
         *
         * Otherwise, Chrome does not work properly: <https://github.com/Genymobile/scrcpy/issues/3635>
         */
        if (source == InputDevice.SOURCE_MOUSE) {
            if (action == MotionEvent.ACTION_DOWN) {
                // First button pressed: ACTION_DOWN
                val downEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_DOWN, pointerCount, pointerProperties,
                    pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0)
                injectInputEvent(downEvent)

                // Any button pressed: ACTION_BUTTON_PRESS
                val pressEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_PRESS, pointerCount, pointerProperties,
                    pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0)
                try {
                    setActionButtonMethod?.invoke(pressEvent, actionButton)
                } catch (e: Exception) {
                    Log.e(RemoteService.TAG, e.message, e)
                }

                injectInputEvent(pressEvent)

                return
            }

            if (action == MotionEvent.ACTION_UP) {
                // Any button released: ACTION_BUTTON_RELEASE
                val releaseEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_RELEASE, pointerCount, pointerProperties,
                    pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0)
                try {
                    setActionButtonMethod?.invoke(releaseEvent, actionButton)
                } catch (e: Exception) {
                    Log.e(RemoteService.TAG, e.message, e)
                }

                injectInputEvent(releaseEvent)

                // Last button released: ACTION_UP
                val upEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_UP, pointerCount, pointerProperties,
                    pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0)
                injectInputEvent(upEvent)

                return
            }
        }

        val event = MotionEvent.obtain(lastTouchDown, now, action, pointerCount, pointerProperties, pointerCoords, 0, buttons, 1f, 1f,
            0, 0, source, 0)
        injectInputEvent(event)
    }

    fun onScrollEvent(x: Float, y: Float, value: Float) {
        mScrollThread.onScrollEvent(x, y, value)
    }

    private fun injectScroll(event: ScrollEvent, value: Float) {
        val now = SystemClock.uptimeMillis()

        val props = pointerProperties[0]!!
        props.id = 0

        val coords = pointerCoords[0]!!
        coords.x = event.x
        coords.y = event.y
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, value)

        val motionEvent = MotionEvent
            .obtain(lastTouchDown, now, MotionEvent.ACTION_SCROLL, 1,
                pointerProperties, pointerCoords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0)
        injectInputEvent(motionEvent)
    }

    private fun injectInputEvent(motionEvent: MotionEvent) {
        try {
            // Set display ID for the motion event using reflection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && setDisplayIdMethod != null) {
                setDisplayIdMethod!!.invoke(motionEvent, displayId)
            }
            injectInputEventMethod!!.invoke(inputManager, motionEvent, displayId)
        } catch (e: Exception) {
            Log.e(RemoteService.TAG, e.message, e)
        }
    }

    private class ScrollEvent {
        var x = 0f
        var y = 0f
        var value = 0f
    }

    private inner class SmoothScrollThread : HandlerThread("scroll") {
        private val event = ScrollEvent()
        var active = false
        var value = 0f
        var DELAY_MS = 50
        private val mHandler: Handler

        init {
            start()
            mHandler = Handler(looper)
        }

        // HandlerThread.sleep was used before; Thread.sleep keeps identical behavior
        fun onScrollEvent(x: Float, y: Float, value: Float) {
            event.x = x
            event.y = y
            this.value += value
            if (!active) mHandler.post(this::scroll)
        }

        fun scroll() {
            active = true
            if (value > 0) {
                event.value = 0.02f
                while (value > 0) {
                    value -= event.value
                    next()
                }
            } else if (value < 0) {
                event.value = -0.02f
                while (value < 0) {
                    value -= event.value
                    next()
                }
            }
            active = false
            DELAY_MS = 50
        }

        private fun next() {
            try {
                Thread.sleep(DELAY_MS.toLong())
                var ivalue = event.value

                val avalue = kotlin.math.abs(value)

                if (avalue > 4) {
                    DELAY_MS = 1
                    ivalue *= avalue - 1
                } else if (avalue > 2.5) {
                    DELAY_MS = 1
                    ivalue *= 2
                } else if (avalue > 1.5) DELAY_MS = 1
                else if (avalue > 0.9) DELAY_MS = 3
                else if (avalue > 0.4) DELAY_MS = 6
                else if (avalue > 0.1) DELAY_MS = 10

                injectScroll(event, ivalue)
            } catch (e: InterruptedException) {
                Log.e(RemoteService.TAG, e.message, e)
            }
        }
    }

    companion object {
        internal var injectInputEventMethod: Method? = null
        internal var inputManager: Any? = null
        internal var setDisplayIdMethod: Method? = null
        internal var setActionButtonMethod: Method? = null

        init {
            var methodName = "getInstance"
            val objArr = arrayOfNulls<Any>(0)
            try {
                val inputManagerClass: Class<*>
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal")
                } else {
                    inputManagerClass = InputManager::class.java
                }

                inputManager = inputManagerClass.getDeclaredMethod(methodName)
                    .invoke(null, *objArr)
                //Make MotionEvent.obtain() method accessible
                methodName = "obtain"
                MotionEvent::class.java.getDeclaredMethod(methodName)
                    .isAccessible = true

                //Get the reference to injectInputEvent method
                methodName = "injectInputEvent"

                injectInputEventMethod = inputManagerClass.getMethod(methodName, InputEvent::class.java, Int::class.javaPrimitiveType)

                // Get the reference to setDisplayId method
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    methodName = "setDisplayId"
                    setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod(methodName, Int::class.javaPrimitiveType)
                    setDisplayIdMethod!!.isAccessible = true
                }
                setActionButtonMethod = MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
                setActionButtonMethod!!.isAccessible = true
            } catch (e: Exception) {
                Log.e(RemoteService.TAG, e.message, e)
            }
        }
    }
}
