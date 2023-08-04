package xtr.keymapper.touchpointer;

import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.BTN_RIGHT;
import static xtr.keymapper.InputEventCodes.REL_WHEEL;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;
import static xtr.keymapper.touchpointer.PointerId.pid1;
import static xtr.keymapper.touchpointer.PointerId.pid2;
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
    private final int pointerId1 = pid1.id;
    private final int pointerId2 = pid2.id;
    private MouseAimHandler mouseAimHandler;
    private KeymapProfileKey rightClick;
    int x1 = 100, y1 = 100;
    int width; int height;
    private final IInputInterface mInput;
    boolean pointer_down;

    public void triggerMouseAim() {
        if (mouseAimHandler != null) {
            mouseAimHandler.active = !mouseAimHandler.active;
            if (mouseAimHandler.active) {
                mouseAimHandler.resetPointer();
                // Notifying user that shooting mode was activated
                try {
                    mInput.getCallback().alertMouseAimActivated(); //post(() -> Toast.makeText(TouchPointer.this, R.string.mouse_aim_activated, Toast.LENGTH_LONG).show());
                } catch (RemoteException e) {
                    e.printStackTrace(System.out);
                }
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

    private void movePointer() {
        mInput.moveCursorX(x1);
        mInput.moveCursorY(y1);
    }

    private void handleRightClick(int value) {
        if (value == 1 && mInput.getKeymapConfig().rightClickMouseAim) triggerMouseAim();
        if (rightClick != null) mInput.injectEvent(rightClick.x, rightClick.y, value, pointerId2);
    }

    public void handleEvent(int code, int value) {
        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        if (mouseAimHandler != null && mouseAimHandler.active) {
            mouseAimHandler.handleEvent(code, value, this::handleRightClick);
            return;
        }
        if (mInput.getKeyEventHandler().ctrlKeyPressed && pointer_down)
            if (keymapConfig.ctrlDragMouseGesture) {
                pointer_down = pinchZoom.handleEvent(code, value);
                return;
            }
        switch (code) {
            case REL_X: {
                if (value == 0) break;
                value *= sensitivity;
                x1 += value;
                if (x1 > width || x1 < 0) x1 -= value;
                if (pointer_down) mInput.injectEvent(x1, y1, MOVE, pointerId1);
                break;
            }
            case REL_Y: {
                if (value == 0) break;
                value *= sensitivity;
                y1 += value;
                if (y1 > height || y1 < 0) y1 -= value;
                if (pointer_down) mInput.injectEvent(x1, y1, MOVE, pointerId1);
                break;
            }
            case BTN_MOUSE:
                pointer_down = value == 1;
                if (mInput.getKeyEventHandler().ctrlKeyPressed && keymapConfig.ctrlDragMouseGesture) {
                    pinchZoom = new MousePinchZoom(mInput, x1, y1);
                    pinchZoom.handleEvent(code, value);
                } else mInput.injectEvent(x1, y1, value, pointerId1);
                break;

            case BTN_RIGHT:
                handleRightClick(value);
                break;

            case REL_WHEEL:
                if (mInput.getKeyEventHandler().ctrlKeyPressed && keymapConfig.ctrlMouseWheelZoom)
                    scrollZoomHandler.onScrollEvent(value, x1, y1);
                else
                    mInput.injectScroll(x1, y1, value * scroll_speed_multiplier);
                break;
        }
        if (code == REL_X || code == REL_Y) movePointer();
    }

    public void stop() {
        mouseAimHandler = null;
        scrollZoomHandler = null;
    }
}
