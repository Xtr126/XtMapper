package xtr.keymapper.server;

import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.BTN_RIGHT;
import static xtr.keymapper.InputEventCodes.REL_WHEEL;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.touchpointer.KeyEventHandler;
import xtr.keymapper.touchpointer.MouseEventHandler;

public class InputService implements IInputInterface {
    private final MouseEventHandler mouseEventHandler;
    private final KeyEventHandler keyEventHandler;
    private KeymapConfig keymapConfig;
    private KeymapProfile keymapProfile;
    private final Input input = new Input();
    public static final int UP = 0, DOWN = 1, MOVE = 2;
    private final IRemoteServiceCallback mCallback;
    boolean stopEvents = false;
    boolean pointerUp = false;
    private final boolean isWaylandClient;
    private final int touchpadInputMode;
    private final View cursorView;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public InputService(KeymapProfile profile, KeymapConfig keymapConfig, IRemoteServiceCallback mCallback, int screenWidth, int screenHeight, View cursorView, boolean isWaylandClient) throws RemoteException {
        this.keymapProfile = profile;
        this.keymapConfig = keymapConfig;
        this.mCallback = mCallback;
        this.isWaylandClient = isWaylandClient;
        this.cursorView = cursorView;

        if (cursorView == null || (keymapConfig.pointerMode != KeymapConfig.POINTER_OVERLAY)) {
            initMouseCursor(screenWidth, screenHeight);
            // Reduce visibility of system pointer
            cursorSetX(0);
            cursorSetY(0);
        }

        this.touchpadInputMode = keymapConfig.touchpadInputMode;
        if (touchpadInputMode == KeymapConfig.TOUCHPAD_DIRECT)
            startTouchpadDirect();
        else if (touchpadInputMode == KeymapConfig.TOUCHPAD_RELATIVE)
            startTouchpadRelative();

        mouseEventHandler = new MouseEventHandler(this);
        mouseEventHandler.init(screenWidth, screenHeight);

        keyEventHandler = new KeyEventHandler(this);
        keyEventHandler.init();
    }

    public void injectEvent(float x, float y, int action, int pointerId) {
        switch (action) {
            case UP:
		pointerUp = true;
                input.injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f, x, y);
                break;
            case DOWN:
		pointerUp = false;
                input.injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f, x, y);
                break;
            case MOVE:
                input.injectTouch(MotionEvent.ACTION_MOVE, pointerId, 1.0f, x, y);
                break;
        }
    }

    public void injectScroll(float x, float y, int value) {
        input.onScrollEvent(x, y, value);
    }

    @Override
    public void pauseResumeKeymap() {
        if (!isWaylandClient) {
            stopEvents = !stopEvents;
            setMouseLock(!stopEvents);
        }
    }

    public KeymapConfig getKeymapConfig() {
        return keymapConfig;
    }

    public KeyEventHandler getKeyEventHandler() {
        return keyEventHandler;
    }

    public MouseEventHandler getMouseEventHandler() {
        return mouseEventHandler;
    }

    @Override
    public KeymapProfile getKeymapProfile() {
        return keymapProfile;
    }

    public IRemoteServiceCallback getCallback() {
        return mCallback;
    }

    public void moveCursorX(float x) {
        mHandler.post(() -> cursorView.setX(x));
        // To avoid conflict with touch input when moving virtual pointer
        if (input.pointerCount < 1) cursorSetX((int) x);
	else if (input.pointerCount == 1 && pointerUp) cursorSetX((int) x);
    }

    public void moveCursorY(float y) {
        mHandler.post(() -> cursorView.setY(y));
        // To avoid conflict with touch input when moving virtual pointer
        if (input.pointerCount < 1) cursorSetY((int) y);
	else if (input.pointerCount == 1 && pointerUp) cursorSetY((int) y);
    }

    @Override
    public void hideCursor() {
        mHandler.post(() -> cursorView.setVisibility(View.INVISIBLE));
    }

    @Override
    public void showCursor() {
        mHandler.post(() -> cursorView.setVisibility(View.VISIBLE));
    }

    public void reloadKeymap() {
        try {
            this.keymapProfile = mCallback.requestKeymapProfile();
            this.keymapConfig = mCallback.requestKeymapConfig();
            this.stop();
            keyEventHandler.init();
            mouseEventHandler.init();
        } catch (Exception e) {
            Log.e("reload keymap", e.getMessage(), e);
        }
    }

    public void stop() {
        keyEventHandler.stop();
        mouseEventHandler.stop();
    }

    public void stopTouchpad() {
        if (touchpadInputMode == KeymapConfig.TOUCHPAD_DIRECT)
            stopTouchpadDirect();
        else if (touchpadInputMode == KeymapConfig.TOUCHPAD_RELATIVE)
            stopTouchpadRelative();
    }

    public native int openDevice(String device);
    public native void stopMouse();
    
    // mouse cursor created with uinput in mouse_cursor.cpp
    public native void cursorSetX(int x);
    public native void cursorSetY(int y);
    private native int initMouseCursor(int width, int height);
    public native void destroyUinputDev();

    public native void setMouseLock(boolean lock);

    // touchpad_direct.cpp
    private native void startTouchpadDirect();
    public native void stopTouchpadDirect();

    private native void startTouchpadRelative();
    public native void stopTouchpadRelative();

    /*
     * Called from native code to send mouse event to client
     */
    public void sendMouseEvent(int code, int value) {
        if (!stopEvents) mouseEventHandler.handleEvent(code, value);
    }

    public void sendWaylandMouseEvent(String line) {
        String[] input_event = line.split("\\s+");
        int value = Integer.parseInt(input_event[3]);
        switch (input_event[2]) {
            case "ABS_X":
                mouseEventHandler.evAbsX(value);
                break;
            case "ABS_Y":
                mouseEventHandler.evAbsY(value);
                break;
            case "REL_WHEEL":
                mouseEventHandler.handleEvent(REL_WHEEL, value);
                break;
            case "BTN_LEFT":
                mouseEventHandler.handleEvent(BTN_MOUSE, value);
                break;
            case "BTN_RIGHT":
                mouseEventHandler.handleEvent(BTN_RIGHT, value);
                break;
            case "REL_X":
                if (mouseEventHandler.mouseAimActive)
                    mouseEventHandler.handleEvent(REL_X, value);
                break;
            case "REL_Y":
                if (mouseEventHandler.mouseAimActive)
                    mouseEventHandler.handleEvent(REL_Y, value);
                break;
        }
    }

}
