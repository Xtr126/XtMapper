package xtr.keymapper.server;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import xtr.keymapper.KeymapConfig;
import xtr.keymapper.profiles.KeymapProfile;
import xtr.keymapper.touchpointer.KeyEventHandler;
import xtr.keymapper.touchpointer.MouseEventHandler;

public class InputService implements IInputInterface {
    private final MouseEventHandler mouseEventHandler;
    private final KeyEventHandler keyEventHandler;
    private KeymapConfig keymapConfig;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private KeymapProfile keymapProfile;
    private static final Input input = new Input();
    public static final int UP = 0, DOWN = 1, MOVE = 2;

    public InputService(){
        mouseEventHandler = new MouseEventHandler(this);
        keyEventHandler = new KeyEventHandler(this, mHandler);

        keyEventHandler.init();
        mouseEventHandler.init();
    }

    public void injectEvent(float x, float y, int action, int pointerId) {
        switch (action) {
            case UP:
                input.injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f, x, y);
                break;
            case DOWN:
                input.injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f, x, y);
                break;
            case MOVE:
                input.injectTouch(MotionEvent.ACTION_MOVE, pointerId, 1.0f, x, y);
                break;
        }
    }

    public void injectScroll(float x, float y, int value) {

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
        cursorSetX(x);
    }

    public void moveCursorY(float y) {
        cursorSetY(y);
    }
    private native void cursorSetX(int x);
    private native void cursorSetY(int y);
    public native int openDevice(String device);
    public native void stopMouse();

    // mouse cursor created with uinput in MouseCursor.cpp
    private native int initMouseCursor(int width, int height);
    private native void destroyUinputDev();

    private native void setMouseLock(boolean lock);

    static {
        System.loadLibrary("mouse_read");
        System.loadLibrary("mouse_cursor");
    }

}
