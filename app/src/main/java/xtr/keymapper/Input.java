package xtr.keymapper;

import static java.lang.Float.parseFloat;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.genymobile.scrcpy.Point;
import com.genymobile.scrcpy.Pointer;
import com.genymobile.scrcpy.PointersState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

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

    static {
        System.loadLibrary("mouse_read");
    }

    private static class InputEvent {
        String action;
        float x, y;
        float pressure = 1.0f;
        int pointerId;

        InputEvent(String line) throws ArrayIndexOutOfBoundsException, NumberFormatException {
            String[] xy = line.split("\\s+");
            this.x = parseFloat(xy[0]);
            this.y = parseFloat(xy[1]);
            this.action = xy[2];
            this.pointerId = Integer.parseInt(xy[3]);
        }
    }

    public void start(Socket socket) {
        try {
            initPointers();
            String line; InputEvent event;

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            while ((line = in.readLine()) != null) {
                try {
                    event = new InputEvent(line);
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    e.printStackTrace(System.out);
                    continue;
                }
                switch (event.action) {
                    case "UP":
                    case "0":
                        event.pressure = 0.0f;
                        injectTouch(MotionEvent.ACTION_UP, event);
                        break;
                    case "DOWN":
                    case "1": {
                        injectTouch(MotionEvent.ACTION_DOWN, event);
                        break;
                    }
                    case "MOVE": {
                        injectTouch(MotionEvent.ACTION_MOVE, event);
                        break;
                    }
                    case "SCROLL": {
                        new SmoothScroll(event).start();
                        break;
                    }
                    case "exit": {
                        System.exit(1);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace(System.out);
        }
    }

    private void injectTouch(int action, InputEvent event) {
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
                TIME = 400, SMOOTHNESS = 40, MULTIPLIER = 2,
                DELAY_MS = TIME / SMOOTHNESS;

        SmoothScroll(InputEvent event) {
            this.event.x = event.x;
            this.event.y = event.y;
            float value = (float) event.pointerId;
            this.event.value =  value / SMOOTHNESS * MULTIPLIER;
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

    public static native void startMouse(int port);

    public static void main(String[] args) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        String methodName = "getInstance";
        Object[] objArr = new Object[0];
        im = (InputManager) InputManager.class.getDeclaredMethod(methodName)
                .invoke(null, objArr);

        //Make MotionEvent.obtain() method accessible
        methodName = "obtain";
        MotionEvent.class.getDeclaredMethod(methodName)
                .setAccessible(true);

        //Get the reference to injectInputEvent method
        methodName = "injectInputEvent";

        injectInputEventMethod = InputManager.class.getMethod(methodName, android.view.InputEvent.class, Integer.TYPE);

        startMouse(Server.DEFAULT_PORT_2); // Call native code
        ServerSocket serverSocket = null;
        final Input input = new Input();

        try {
            serverSocket = new ServerSocket(Server.DEFAULT_PORT);
        } catch (IOException e) {
            e.printStackTrace(System.out);
            System.exit(2);
        }

        while (true) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(() -> input.start(socket)).start();
            } catch (IOException e) {
                System.out.println("I/O error: " + e);
            }
        }
    }
}
