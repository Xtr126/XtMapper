package xtr.keymapper.touchpointer;

import static xtr.keymapper.InputEventCodes.BTN_EXTRA;
import static xtr.keymapper.InputEventCodes.BTN_MIDDLE;
import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.BTN_RIGHT;
import static xtr.keymapper.InputEventCodes.BTN_SIDE;
import static xtr.keymapper.InputEventCodes.REL_WHEEL;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;
import static xtr.keymapper.server.InputService.MOVE;

import android.os.RemoteException;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.mouse.MouseAimHandler;
import xtr.keymapper.mouse.MousePinchZoom;
import xtr.keymapper.mouse.MouseWheelZoom;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.KeymapProfileKey;
import xtr.keymapper.server.IInputInterface;

public class MouseEventHandler {
    int sensitivity = 1;
    int scroll_speed_multiplier = 1;
    private MousePinchZoom pinchZoom;
    private MouseWheelZoom scrollZoomHandler;
    private final int pointerId = PointerId.pid1.id;
    private final int pointerIdRightClick = PointerId.pid3.id;
    private MouseAimHandler mouseAimHandler;
    private KeymapProfileKey rightClick;
    int x1 = 100, y1 = 100;
    int width; int height;
    private final IInputInterface mInput;
    boolean pointer_down;
    public boolean mouseAimActive = false;

    public void triggerMouseAim() {
        if (mouseAimHandler != null) {
            mouseAimActive = !mouseAimActive;
            if (mouseAimActive) {
                mouseAimHandler.resetPointer();
                // Notifying user that shooting mode was activated
                try {
                    mInput.getCallback().alertMouseAimActivated();
                } catch (RemoteException e) {
                    e.printStackTrace(System.out);
                }
                mInput.hideCursor();
            } else {
                mouseAimHandler.stop();
                mInput.showCursor();
            }
        }
    }

    public MouseEventHandler(IInputInterface mInput) {
        this.mInput = mInput;
    }

    public void init(){
        init(width, height);
    }

    public void init(int width, int height) {
        this.width = width;
        this.height = height;

        KeymapProfile profile = mInput.getKeymapProfile();
        if (profile.mouseAimConfig != null)
            mouseAimHandler = new MouseAimHandler(profile.mouseAimConfig);
        this.rightClick = profile.rightClick;

        if (mouseAimHandler != null) {
            mouseAimHandler.setInterface(mInput);
            mouseAimHandler.setDimensions(width, height);
        }

        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        if (keymapConfig.ctrlMouseWheelZoom)
            scrollZoomHandler = new MouseWheelZoom(mInput);

        sensitivity = keymapConfig.mouseSensitivity.intValue();
        scroll_speed_multiplier = keymapConfig.scrollSpeed.intValue();
    }

    private void movePointerX() {
        mInput.moveCursorX(x1);
    }

    private void movePointerY() {
        mInput.moveCursorY(y1);
    }

    private void handleRightClick(int value) {
        if (value == 1 && mInput.getKeymapConfig().rightClickMouseAim) triggerMouseAim();
        else if (rightClick != null)
            mInput.injectEvent(rightClick.x, rightClick.y, value, pointerIdRightClick);
    }

    public void handleEvent(int code, int value) {
        if (mouseAimHandler != null && mouseAimActive) {
            mouseAimHandler.handleEvent(code, value, this::handleMouseEvent);
        } else handleMouseEvent(code, value);
    }

    private void handleMouseEvent(int code, int value) {
        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        if (mInput.getKeyEventHandler().ctrlKeyPressed && pointer_down)
            if (keymapConfig.ctrlDragMouseGesture) {
                if (pinchZoom != null) pointer_down = pinchZoom.handleEvent(code, value);
                return;
            }
        switch (code) {
            case REL_X: {
                if (value == 0) break;
                value *= sensitivity;
                x1 += value;
                if (x1 > width || x1 < 0) x1 -= value;
                if (pointer_down) mInput.injectEvent(x1, y1, MOVE, pointerId);
                else mInput.injectHoverEvent(x1, y1, pointerId);
                break;
            }
            case REL_Y: {
                if (value == 0) break;
                value *= sensitivity;
                y1 += value;
                if (y1 > height || y1 < 0) y1 -= value;
                if (pointer_down) mInput.injectEvent(x1, y1, MOVE, pointerId);
                else mInput.injectHoverEvent(x1, y1, pointerId);
                break;
            }
            case BTN_MOUSE:
                pointer_down = value == 1;
                if (mInput.getKeyEventHandler().ctrlKeyPressed && keymapConfig.ctrlDragMouseGesture) {
                    pinchZoom = new MousePinchZoom(mInput, x1, y1);
                    pinchZoom.handleEvent(code, value);
                } else mInput.injectEvent(x1, y1, value, pointerId);
                break;

            case BTN_RIGHT:
                handleRightClick(value);
                break;

            case BTN_EXTRA:
            case BTN_SIDE:
            case BTN_MIDDLE:
                if (value == 1) triggerMouseAim();

            case REL_WHEEL:
                if (mInput.getKeyEventHandler().ctrlKeyPressed && keymapConfig.ctrlMouseWheelZoom)
                    scrollZoomHandler.onScrollEvent(value, x1, y1);
                else
                    mInput.injectScroll(x1, y1, value * scroll_speed_multiplier);
                break;
        }
        if (code == REL_X) movePointerX();
        if (code == REL_Y) movePointerY();
    }

    public void evAbsY(int y) {
        this.y1 = y;
        mInput.injectEvent(x1, y1, MOVE, pointerId);
        movePointerY();
    }

    public void evAbsX(int x) {
        this.x1 = x;
        mInput.injectEvent(x1, y1, MOVE, pointerId);
        movePointerX();
    }

    public void stop() {
        scrollZoomHandler = null;
        pinchZoom = null;
        mouseAimHandler = null;
    }
}
