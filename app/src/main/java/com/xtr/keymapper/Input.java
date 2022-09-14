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


    public void start(String[] args) {
        if(!args[0].equals("null")) {
            startMouse(args[0]); // Call native code
        } else {
            System.out.println("exiting: input device not selected");
            System.out.println("select input device, click run in terminal and try again\n or edit this script and replace null with input device node");
            System.exit(2);
        }

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
        ServerSocket ss=new ServerSocket(MainActivity.DEFAULT_PORT);
        System.out.println("waiting for overlay...");
        Socket socket=ss.accept();
        BufferedReader stdInput = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        System.out.println("connected at port: " + MainActivity.DEFAULT_PORT);
        while ((line = stdInput.readLine()) != null) {
            System.out.println(line);
            String []xy = line.split("\\s+");
            int pointerId = Integer.parseInt(xy[3]);
            switch (xy[2]) {
                case "UP":
                case "0": {
                    /*injectTouch(inputSource, parseFloat(xy[0]), parseFloat(xy[1]),
                            MotionEvent.ACTION_UP, 0.0f);*/
                    injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f,
                            parseFloat(xy[0]), parseFloat(xy[1]));
                    break;
                }
                case "DOWN":
                case "1": {
                    /*injectTouch(inputSource, parseFloat(xy[0]), parseFloat(xy[1]),
                            MotionEvent.ACTION_DOWN, 1.0f);*/
                    injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f,
                            parseFloat(xy[0]), parseFloat(xy[1]));
                    break;
                }
                case "MOVE": {
                    /*injectTouch(inputSource, parseFloat(xy[0]), parseFloat(xy[1]),
                            MotionEvent.ACTION_MOVE, 1.0f);*/
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
            System.out.println(e);
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

    private void injectTouch(int inputSource, float x, float y, int action, float pressure) {
        long now = SystemClock.uptimeMillis();
        final float DEFAULT_SIZE = 1.0f;
        final int DEFAULT_META_STATE = 0;
        final float DEFAULT_PRECISION_X = 1.0f;
        final float DEFAULT_PRECISION_Y = 1.0f;
        final int DEFAULT_EDGE_FLAGS = 0;
        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchDown = now;
        }
       /* MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, x, y, pressure, DEFAULT_SIZE,
                DEFAULT_META_STATE, DEFAULT_PRECISION_X, DEFAULT_PRECISION_Y,
                inputSource, DEFAULT_EDGE_FLAGS);*/
        /*int i = 0;
        pointerProperties[i].id = 0;
        pointerCoords[i].x = x;
        pointerCoords[i].y = y;
        pointerCoords[i].pressure = pressure;*/
        pointersState.update(pointerProperties, pointerCoords);
        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, 1,
                pointerProperties, pointerCoords,
                DEFAULT_META_STATE, 0, DEFAULT_PRECISION_X, DEFAULT_PRECISION_Y,
                0, DEFAULT_EDGE_FLAGS, InputDevice.SOURCE_TOUCHSCREEN, 0);
        event.setSource(inputSource);
        try {
            injectInputEventMethod.invoke(im, event, 2);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    static {
        System.loadLibrary("mouse_read");
    }
    public static native void startMouse(String arg);

    public static native void setIoctl(boolean y);

    public static void main(String[] args) {
        Input input=new Input();
        input.start(args);
    }
}
