package xtr.keymapper.server.event;

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
import android.util.Log;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.mouse.MouseAimHandler;
import xtr.keymapper.mouse.MousePinchZoom;
import xtr.keymapper.mouse.MouseWalkHandler;
import xtr.keymapper.mouse.MouseWheelZoom;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.element.Key;
import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.server.RemoteService;
import xtr.keymapper.server.pid.PointerId;

public class MouseEventHandler {
    float sensitivity;
    float scroll_speed_multiplier;
    private MousePinchZoom pinchZoom;
    private MouseWheelZoom scrollZoomHandler;
    private final int pointerId = PointerId.pid1.id;
    private final int pointerIdRightClick = PointerId.pid3.id;
    private MouseAimHandler mouseAimHandler;
    private MouseAimHandler mouseCameraHandler;
    private Key rightClick;
    int x1 = 100, y1 = 100;
    int width; int height;
    private final IInputInterface mInput;
    boolean pointer_down;
    public boolean mouseAimActive = false;
    public boolean mouseWalkActive = false;
    private MouseAimHandler mouseAimOrCameraHandler;
    private MouseWalkHandler mouseWalkHandler;

    public void triggerMouseAim() {
        triggerMouseAimOrCamera(mouseAimHandler);
    }


    public void triggerCamera() {
        triggerMouseAimOrCamera(mouseCameraHandler);
    }

    private void triggerMouseAimOrCamera(MouseAimHandler instance) {
        mouseAimOrCameraHandler = instance;
        if (instance != null) {
            mouseAimActive = !mouseAimActive;
            if (mouseAimActive) {
                instance.resetPointer();
                // Notifying user that shooting mode was activated
                try {
                    mInput.getCallback().alertMouseAimActivated();
                } catch (RemoteException e) {
                    Log.e(RemoteService.TAG, e.getMessage(), e);
                }
                mInput.hideCursor();
            } else {
                instance.stop();
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
        if (profile.camera != null)
            mouseCameraHandler = new MouseAimHandler(profile.camera);
        if (profile.mouseWalk != null)
            mouseWalkHandler = new MouseWalkHandler(profile.mouseWalk);

        this.rightClick = profile.rightClick;

        if (mouseAimHandler != null) {
            mouseAimHandler.setInterface(mInput);
            mouseAimHandler.setDimensions(width, height);
        }
        if (mouseCameraHandler != null) {
            mouseCameraHandler.setInterface(mInput);
            mouseCameraHandler.setDimensions(width, height);
        }

        if (mouseWalkHandler != null) {
            mouseWalkHandler.setInterface(mInput);
            mouseWalkHandler.setDimensions(width, height);
        }

        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        if (keymapConfig.ctrlMouseWheelZoom)
            scrollZoomHandler = new MouseWheelZoom(mInput);

        sensitivity = keymapConfig.mouseSensitivity;
        scroll_speed_multiplier = keymapConfig.scrollSpeed;
    }

    private void movePointerX() {
        if (mouseWalkActive) mouseWalkHandler.onCursorPosition(x1, y1);
        mInput.moveCursorX(x1);
    }

    private void movePointerY() {
        if (mouseWalkActive) mouseWalkHandler.onCursorPosition(x1, y1);
        mInput.moveCursorY(y1);
    }

    private void handleRightClick(int value) {
        if (value == 1) {
            if (mouseWalkHandler != null) {
                if (mouseWalkActive) {
                    mouseWalkActive = false;
                    mouseWalkHandler.stop();
                } else {
                    mouseWalkHandler.resetPointer();
                    mouseWalkActive = true;
                }
            }
            else if (mInput.getKeymapConfig().rightClickMouseAim)
                triggerMouseAim();
        }
        else if (rightClick != null)
            mInput.injectEvent(rightClick.x, rightClick.y, value, pointerIdRightClick);
    }

    public void handleEvent(int code, int value) {
        if (mouseAimOrCameraHandler != null && mouseAimActive) {
            mouseAimOrCameraHandler.handleEvent(code, value, this::handleMouseEvent);
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
                value = (int) (value*sensitivity);
                if (value == 0) break;
                x1 += value;
                if (x1 > width || x1 < 0) x1 -= value;
                if (pointer_down) mInput.injectEvent(x1, y1, MOVE, pointerId);
                else mInput.injectHoverEvent(x1, y1, pointerId);
                break;
            }
            case REL_Y: {
                value = (int) (value*sensitivity);
                if (value == 0) break;
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
        if (pointer_down) mInput.injectEvent(x1, y1, MOVE, pointerId);
        else mInput.injectHoverEvent(x1, y1, pointerId);
        movePointerY();
    }

    public void evAbsX(int x) {
        this.x1 = x;
        if (pointer_down) mInput.injectEvent(x1, y1, MOVE, pointerId);
        else mInput.injectHoverEvent(x1, y1, pointerId);
        movePointerX();
    }

    public void stop() {
        scrollZoomHandler = null;
        pinchZoom = null;
        mouseAimHandler = null;
        mouseCameraHandler = null;
        mouseWalkHandler = null;
    }
}
