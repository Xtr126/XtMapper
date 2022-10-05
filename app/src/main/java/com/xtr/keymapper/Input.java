package com.xtr.keymapper;

import static java.lang.Float.parseFloat;

import android.hardware.input.InputManager;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;

import com.genymobile.scrcpy.Point;
import com.genymobile.scrcpy.Pointer;
import com.genymobile.scrcpy.PointersState;

public class Input {

    static Method injectInputEventMethod;
    static InputManager im;
    static int inputSource = InputDevice.SOURCE_UNKNOWN;

    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];
    private long lastTouchDown;


    public void start(Socket socket) {
    try {
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

        inputSource = getSource(inputSource);
        injectInputEventMethod = InputManager.class.getMethod(methodName, InputEvent.class, Integer.TYPE);
        initPointers();
        String line;
        System.out.println("waiting for overlay...");
        BufferedReader stdInput = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        System.out.println("connected at port: " + MainActivity.DEFAULT_PORT);
        while ((line = stdInput.readLine()) != null) {
            System.out.println(line);
            String []xy = line.split("\\s+");
            int pointerId;
            try {
                pointerId = Integer.parseInt(xy[3]);
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                e.printStackTrace(System.out);
                continue;
            }
            switch (xy[2]) {
                case "UP":
                case "0": {
                    injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f,
                            parseFloat(xy[0]), parseFloat(xy[1]));
                    break;
                }
                case "DOWN":
                case "1": {
                    injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f,
                            parseFloat(xy[0]), parseFloat(xy[1]));
                    break;
                }
                case "MOVE": {
                    injectTouch(MotionEvent.ACTION_MOVE, pointerId, 1.0f,
                    parseFloat(xy[0]), parseFloat(xy[1]));
                    break;
                }
                case "ioctl": {
                    setIoctl(xy[1].equals("true"));
                    break;
                }
            }
        }
        } catch (NoSuchMethodException |
                IllegalAccessException |
                InvocationTargetException |
                IOException e) {
            e.printStackTrace(System.out);
        }
    }

    private static int getSource(int inputSource) {
        return inputSource == InputDevice.SOURCE_UNKNOWN ?
                InputDevice.SOURCE_TOUCHSCREEN : inputSource;
    }

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

    private void injectTouch(int action, long pointerId, float pressure, float x, float y) {
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
        int source = InputDevice.SOURCE_TOUCHSCREEN;
        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, pointerCount,
                        pointerProperties, pointerCoords,
                        0, 0, 1f, 1f,
                        0, 0, source, 0);
        event.setSource(inputSource);
        try {
            injectInputEventMethod.invoke(im, event, 0);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace(System.out);
        }
    }

    static {
        System.loadLibrary("mouse_read");
    }
    public static native void startMouse(String arg0, int arg1);

    public static native void setIoctl(boolean y);

    public static void main(String[] args) {
        if(!args[0].equals("null")) {
            startMouse(args[0], MainActivity.DEFAULT_PORT_2); // Call native code
        } else {
            System.out.println("exiting: input device not selected");
            System.out.println("select input device, click run in terminal and try again\n or edit this script and replace null with input device node");
            System.exit(2);
        }
        ServerSocket serverSocket = null;
        Input input = new Input();

        try {
            serverSocket = new ServerSocket(MainActivity.DEFAULT_PORT);
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


