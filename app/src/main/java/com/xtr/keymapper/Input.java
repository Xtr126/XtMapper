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



public class Input {

    static Method injectInputEventMethod;
    static InputManager im;
    static int inputSource = InputDevice.SOURCE_UNKNOWN;
    public static void main(String[] args) {
        try {
        String methodName = "getInstance";
        Object[] objArr = new Object[0];
        im = (InputManager) InputManager.class.getDeclaredMethod(methodName, new Class[0])
                .invoke(null, objArr);

        //Make MotionEvent.obtain() method accessible
        methodName = "obtain";
        MotionEvent.class.getDeclaredMethod(methodName, new Class[0])
                .setAccessible(true);

        //Get the reference to injectInputEvent method
        methodName = "injectInputEvent";

        inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
        injectInputEventMethod = InputManager.class.getMethod(methodName, new Class[] {InputEvent.class, Integer.TYPE});
        String line;
        ServerSocket ss=new ServerSocket(MainActivity.DEFAULT_PORT);
        Socket socket=ss.accept();
        BufferedReader stdInput = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        System.out.println("server started at" + MainActivity.DEFAULT_PORT);
        while ((line = stdInput.readLine()) != null) {
            System.out.println(line);
            String []xy = line.split("\\s+");
            switch (xy[2]) {
                case "UP": {
                    sendTapUp(inputSource, parseFloat(xy[0]), parseFloat(xy[1]));
                    break;
                }
                case "DOWN": {
                    sendTapDown(inputSource, parseFloat(xy[0]), parseFloat(xy[1]));
                    break;
                }
                case "MOVE": {
                    sendMove(inputSource,
                            parseFloat(xy[0]),
                            parseFloat(xy[1]),
                            parseFloat(xy[3]),
                            parseFloat(xy[4]));
                }
            }
        }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | IOException e) {
            System.out.println(e);
        }
    }




    private static float lerp(float a, float b, float alpha) {
        return (b - a) * alpha + a;
    }
    private static int getSource(int inputSource, int defaultSource) {
        return inputSource == InputDevice.SOURCE_UNKNOWN ? defaultSource : inputSource;
    }
   
    private static void injectMotionEvent(int inputSource, int action, long when, float x, float y, float pressure) {
        final float DEFAULT_SIZE = 1.0f;
        final int DEFAULT_META_STATE = 0;
        final float DEFAULT_PRECISION_X = 1.0f;
        final float DEFAULT_PRECISION_Y = 1.0f;
        final int DEFAULT_EDGE_FLAGS = 0;
        MotionEvent event = MotionEvent.obtain(when, when, action, x, y, pressure, DEFAULT_SIZE,
                DEFAULT_META_STATE, DEFAULT_PRECISION_X, DEFAULT_PRECISION_Y,
                inputSource, DEFAULT_EDGE_FLAGS);
        event.setSource(inputSource);

        try {
            injectInputEventMethod.invoke(im, new Object[] {event, Integer.valueOf(2)});
        } catch (IllegalAccessException | InvocationTargetException e) {
        e.printStackTrace();
    }
    }
    private static void sendTap(int inputSource, float x, float y) {
        long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, now, x, y, 1.0f);
        injectMotionEvent(inputSource, MotionEvent.ACTION_UP, now, x, y, 0.0f);
    }
    private static void sendTapUp(int inputSource, float x, float y) {
        long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_UP, now, x, y, 0.0f);
    }
    private static void sendTapDown(int inputSource, float x, float y) {
        long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, now, x, y, 1.0f);
    }

    private static void sendMove(int inputSource, float x1, float y1, float x2, float y2) {
        long now = SystemClock.uptimeMillis();
            injectMotionEvent(inputSource, MotionEvent.ACTION_MOVE, now, lerp(x1, x2, now),
                    lerp(y1, y2, now), 1.0f);
    }

}
