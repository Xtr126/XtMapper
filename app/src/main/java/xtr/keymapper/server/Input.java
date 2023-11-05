package xtr.keymapper.server;

import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
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
    private final SmoothScroll scrollHandler = new SmoothScroll();
    private Handler mHandler;

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

    public Input() {
        initPointers();
    }

    public void injectTouch(int action, int pointerId, float pressure, float x, float y) {
        long now = SystemClock.uptimeMillis();
        Point point = new Point(x, y);

        int pointerIndex = pointersState.getPointerIndex(pointerId);
        if (pointerIndex == -1) {
            System.out.println("Too many pointers for touch event");
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(pressure);
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

    public void onScrollEvent(float x, float y, int value){
        if (mHandler == null) mHandler = new Handler(scrollHandler.getLooper());
        scrollHandler.onScrollEvent(x, y, value);
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

    private class SmoothScroll extends HandlerThread {
        private final ScrollEvent event = new ScrollEvent();
        boolean active = false;
        float value = 0;
        int DELAY_MS = 50;

        public SmoothScroll() {
            super("scroll");
            start();
        }

        public void onScrollEvent(float x, float y, int value) {
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
