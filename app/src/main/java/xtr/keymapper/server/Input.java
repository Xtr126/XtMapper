package xtr.keymapper.server;

import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.genymobile.scrcpy.Point;
import com.genymobile.scrcpy.Pointer;
import com.genymobile.scrcpy.PointersState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import xtr.keymapper.server.event.MouseEventHandler;

public class Input {

    static Method injectInputEventMethod;
    static Object inputManager;
    static Method setDisplayIdMethod;
    static Method setActionButtonMethod;

    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];
    private final int displayId;
    private long lastTouchDown;
    private int pointerCount = 0;

    private final SmoothScrollThread mScrollThread = new SmoothScrollThread();


    private void initPointers() {
        for (int i = 0; i < PointersState.MAX_POINTERS; ++i) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;

            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.orientation = 0;
            coords.size = 0;

            pointerProperties[i] = props;
            pointerCoords[i] = coords;
        }
    }

    public boolean noPointersDown() {
        if (pointerCount > 0) {
            pointerCount = pointersState.update(pointerProperties, pointerCoords);
            for (int i = 0; i < pointerCount; i++) {
                if (!pointersState.get(i).isUp()) return false;
            }
        }
        return true;
    }

    public Input(int displayId) {
        this.displayId = displayId;
        initPointers();
    }

    public void injectTouch(int action, int pointerId, float pressure, float x, float y) {
        long now = SystemClock.uptimeMillis();
        Point point = new Point(x, y);

        int pointerIndex = pointersState.getPointerIndex(pointerId);
        if (pointerIndex == -1) {
            Log.e(RemoteService.TAG, "Too many pointers for touch event");
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(pressure);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_HOVER_MOVE)
            pointer.setUp(true);
        else if (action == MotionEvent.ACTION_DOWN) pointer.setUp(false);

        int source = InputDevice.SOURCE_TOUCHSCREEN;

        pointerCount = pointersState.update(pointerProperties, pointerCoords);

        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now;
            }
        } else {
            // secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP |
                        (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else if (action == MotionEvent.ACTION_DOWN) {
                action = MotionEvent.ACTION_POINTER_DOWN |
                        (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        }
        MotionEvent motionEvent = MotionEvent.obtain(lastTouchDown, now, action, pointerCount,
                pointerProperties, pointerCoords,
                0, 0, 1f, 1f,
                0, 0, source, 0);
        injectInputEvent(motionEvent);
    }

    void injectRightClick(long pointerId, float x, float y, boolean pressed) {
        long now = SystemClock.uptimeMillis();

        final float pressure = 1.0f;
        final int actionButton = MotionEvent.BUTTON_SECONDARY;
        int action = pressed ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
        int buttons = pressed ? MotionEvent.BUTTON_SECONDARY : 0;

        Point point = new Point(x, y);

        int pointerIndex = pointersState.getPointerIndex(pointerId);
        if (pointerIndex == -1) {
            Log.e(RemoteService.TAG, "Too many pointers for touch event");
        }

        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(pressure);

        int source;
        if (pointerId == MouseEventHandler.pointerId) {
            // real mouse event, or event incompatible with a finger
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            source = InputDevice.SOURCE_MOUSE;
            pointer.setUp(buttons == 0);
        } else {
            // POINTER_ID_GENERIC_FINGER, POINTER_ID_VIRTUAL_FINGER or real touch from device
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_FINGER;
            source = InputDevice.SOURCE_TOUCHSCREEN;
            // Buttons must not be set for touch events
            buttons = 0;
            pointer.setUp(action == MotionEvent.ACTION_UP);
        }

        int pointerCount = pointersState.update(pointerProperties, pointerCoords);
        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now;
            }
        } else {
            // secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else {
                action = MotionEvent.ACTION_POINTER_DOWN | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
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
                MotionEvent downEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_DOWN, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0);
                injectInputEvent(downEvent);

                // Any button pressed: ACTION_BUTTON_PRESS
                MotionEvent pressEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_PRESS, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0);
                try {
                    if (setActionButtonMethod != null) {
                        setActionButtonMethod.invoke(pressEvent, actionButton);
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    Log.e(RemoteService.TAG, e.getMessage(), e);
                }

                injectInputEvent(pressEvent);

                return;
            }

            if (action == MotionEvent.ACTION_UP) {
                // Any button released: ACTION_BUTTON_RELEASE
                MotionEvent releaseEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_RELEASE, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0);
                try {
                    if (setActionButtonMethod != null) {
                        setActionButtonMethod.invoke(releaseEvent, actionButton);
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    Log.e(RemoteService.TAG, e.getMessage(), e);
                }

                injectInputEvent(releaseEvent);

                // Last button released: ACTION_UP
                MotionEvent upEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_UP, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, 0, 0, source, 0);
                injectInputEvent(upEvent);

                return;
            }
        }

        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, pointerCount, pointerProperties, pointerCoords, 0, buttons, 1f, 1f,
                0, 0, source, 0);
        injectInputEvent(event);
    }


    public void onScrollEvent(float x, float y, float value){
        mScrollThread.onScrollEvent(x, y, value);
    }

    private void injectScroll(ScrollEvent event, float value) {
        long now = SystemClock.uptimeMillis();

        MotionEvent.PointerProperties props = pointerProperties[0];
        props.id = 0;

        MotionEvent.PointerCoords coords = pointerCoords[0];
        coords.x = event.x;
        coords.y = event.y;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, value);

        MotionEvent motionEvent = MotionEvent
                .obtain(lastTouchDown, now, MotionEvent.ACTION_SCROLL, 1,
                        pointerProperties, pointerCoords,
                        0, 0, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0);
            injectInputEvent(motionEvent);
    }

    private void injectInputEvent(MotionEvent motionEvent)  {
        try {
            // Set display ID for the motion event using reflection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && setDisplayIdMethod != null) {
                setDisplayIdMethod.invoke(motionEvent, displayId);
            }
            injectInputEventMethod.invoke(inputManager, motionEvent, displayId);
        } catch (IllegalAccessException | InvocationTargetException e) {
            Log.e(RemoteService.TAG, e.getMessage(), e);
        }
    }

    private static class ScrollEvent {
        float x;
        float y;
        float value;
    }

    private class SmoothScrollThread extends HandlerThread {
        private final ScrollEvent event = new ScrollEvent();
        boolean active = false;
        float value = 0;
        int DELAY_MS = 50;
        private final Handler mHandler;

        public SmoothScrollThread() {
            super("scroll");
            start();
            mHandler = new Handler(getLooper());
        }

        public void onScrollEvent(float x, float y, float value) {
            event.x = x;
            event.y = y;
            this.value += value;
            if (!active) mHandler.post(this::scroll);
        }

        public void scroll() {
            active = true;
            if (value > 0) {
                event.value = 0.02f;
                while (value > 0)  {
                    value -= event.value;
                    next();
                }
            } else
            if (value < 0) {
                event.value = -0.02f;
                while (value < 0) {
                    value -= event.value;
                    next();
                }
            }
            active = false;
            DELAY_MS = 50;
        }

        private void next() {
            try {
                sleep(DELAY_MS);
                float ivalue = event.value;

                float avalue = Math.abs(value);

                if (avalue > 4) {
                    DELAY_MS = 1;
                    ivalue *= avalue - 1;
                } else
                if (avalue > 2.5) {
                    DELAY_MS = 1;
                    ivalue *= 2;
                } else
                if (avalue > 1.5) DELAY_MS = 1;
                else if (avalue > 0.9) DELAY_MS = 3;
                else if (avalue > 0.4) DELAY_MS = 6;
                else if (avalue > 0.1) DELAY_MS = 10;

                injectScroll(event, ivalue);
            } catch (InterruptedException e) {
                Log.e(RemoteService.TAG, e.getMessage(), e);
            }
        }
    }

    static {
        String methodName = "getInstance";
        Object[] objArr = new Object[0];
         try {
             Class<?> inputManagerClass;
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
             } else {
                 inputManagerClass = InputManager.class;
             }

             inputManager = inputManagerClass.getDeclaredMethod(methodName)
                     .invoke(null, objArr);
             //Make MotionEvent.obtain() method accessible
             methodName = "obtain";
             MotionEvent.class.getDeclaredMethod(methodName)
                     .setAccessible(true);

             //Get the reference to injectInputEvent method
             methodName = "injectInputEvent";

             injectInputEventMethod = inputManagerClass.getMethod(methodName, InputEvent.class, Integer.TYPE);

             // Get the reference to setDisplayId method
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                 methodName = "setDisplayId";
                 setDisplayIdMethod = MotionEvent.class.getDeclaredMethod(methodName, Integer.TYPE);
                 setDisplayIdMethod.setAccessible(true);
             }
             setActionButtonMethod = MotionEvent.class.getDeclaredMethod("setActionButton", int.class);
             setActionButtonMethod.setAccessible(true);
         } catch (Exception e) {
             Log.e(RemoteService.TAG, e.getMessage(), e);
         }
    }
}
