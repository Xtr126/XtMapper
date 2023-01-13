package xtr.keymapper.server;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.genymobile.scrcpy.Point;
import com.genymobile.scrcpy.Pointer;
import com.genymobile.scrcpy.PointersState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class Input {

    static Method injectInputEventMethod;
    static InputManager im;
    static int inputSource = InputDevice.SOURCE_TOUCHSCREEN;

    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];
    private long lastTouchDown;


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

    static class InjectEvent {
        float x, y;
        float pressure = 1.0f;
        int pointerId;

        InjectEvent(float x, float y, int pointerId) {
            this.x = x;
            this.y = y;
            this.pointerId = pointerId;
        }
    }

    public Input() {
        initPointers();
    }

    private void injectTouch(int action, InjectEvent event) {
        long now = SystemClock.uptimeMillis();
        Point point = new Point(event.x, event.y);

        int pointerIndex = pointersState.getPointerIndex(event.pointerId);
        if (pointerIndex == -1) {
            System.out.println("Too many pointers for touch event");
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(event.pressure);
        pointer.setUp(action == MotionEvent.ACTION_UP);

        int pointerCount = pointersState.update(pointerProperties, pointerCoords);

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
                0, 0, inputSource, 0);
        try {
            injectInputEventMethod.invoke(im, motionEvent, 0);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace(System.out);
        }
    }

    public void injectTouch(int action, int pointerId, float pressure, float x, float y) {
        InjectEvent event = new InjectEvent(x, y, pointerId);
        event.pressure = pressure;
        injectTouch(action, event);
    }

    public void onScrollEvent(float x, float y, int value){
        new SmoothScroll(x, y, value).start();
    }

    private void injectScroll(ScrollEvent event) {
        long now = SystemClock.uptimeMillis();

        MotionEvent.PointerProperties props = pointerProperties[0];
        props.id = 0;

        MotionEvent.PointerCoords coords = pointerCoords[0];
        coords.x = event.x;
        coords.y = event.y;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, event.value);

        MotionEvent motionEvent = MotionEvent
                .obtain(lastTouchDown, now, MotionEvent.ACTION_SCROLL, 1,
                        pointerProperties, pointerCoords,
                        0, 0, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0);
        try {
            injectInputEventMethod.invoke(im, motionEvent, 0);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace(System.out);
        }
    }

    private static class ScrollEvent {
        float x;
        float y;
        float value;
    }

    private class SmoothScroll extends Thread {
        private final ScrollEvent event = new ScrollEvent();
        private static final int
                TIME = 100, SMOOTHNESS = 20,
                DELAY_MS = TIME / SMOOTHNESS;

        SmoothScroll(float x, float y, int value) {
            this.event.x = x;
            this.event.y = y;
            this.event.value =  (float) value / SMOOTHNESS;
        }

        public void run() {
            try {
                for(int i = 0; i < SMOOTHNESS; i++) {
                    injectScroll(event);
                    sleep(DELAY_MS);
                }
            } catch (InterruptedException e) {
                e.printStackTrace(System.out);
            }
        }
    }

    static {
        String methodName = "getInstance";
        Object[] objArr = new Object[0];
         try {
             im = (InputManager) InputManager.class.getDeclaredMethod(methodName)
                     .invoke(null, objArr);
             //Make MotionEvent.obtain() method accessible
             methodName = "obtain";
             MotionEvent.class.getDeclaredMethod(methodName)
                     .setAccessible(true);

             //Get the reference to injectInputEvent method
             methodName = "injectInputEvent";

             injectInputEventMethod = InputManager.class.getMethod(methodName, android.view.InputEvent.class, Integer.TYPE);

         } catch (Exception e) {
            e.printStackTrace(System.out);
         }
    }
}
