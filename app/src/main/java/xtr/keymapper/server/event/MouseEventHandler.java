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

import java.util.Objects;

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

    /* SoufianoDev:
     * Concurrency Design – Cross-Thread Visibility
     * =============================================
     * The native input thread reads mouse state while the main thread updates it.
     * Without proper memory visibility, the native thread may see stale values,
     * leading to missed clicks, stuck pointers, or aim mode not being activated.
     *
     * The following fields are declared volatile to guarantee visibility:
     *   - pointer_down: Ensures the native thread sees the latest press/release.
     *   - mouseAimActive: Toggles aim mode; must be visible to the native thread.
     *   - mouseAimOrCameraHandler: The handler reference assigned before the flag.
     *     Without volatile, the compiler could reorder writes, causing the native
     *     thread to see mouseAimActive == true but a null handler → crash.
     *
     * Additionally, triggerMouseAimOrCamera() uses a synchronized block to make
     * the assignment of mouseAimOrCameraHandler and the flag flip atomic.
     */
    private volatile boolean pointer_down;
    public volatile boolean mouseAimActive = false;
    private volatile MouseAimHandler mouseAimOrCameraHandler;

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
    int width;
    int height;
    private final IInputInterface mInput;

    private boolean mouseWalkActive = false;
    private MouseWalkHandler mouseWalkHandler;

    public boolean triggerMouseAim() {
        return triggerMouseAimOrCamera(mouseAimHandler);
    }

    public void triggerCamera() {
        triggerMouseAimOrCamera(mouseCameraHandler);
    }

    private boolean triggerMouseAimOrCamera(MouseAimHandler instance) {
        if (instance == null) return false;

        synchronized (this) {
            mouseAimOrCameraHandler = instance;
            mouseAimActive = !mouseAimActive;

            if (mouseAimActive) {
                stopMouseWalk();
                instance.resetPointer();
                try {
                    mInput.getCallback().alertMouseAimActivated();
                } catch (RemoteException e) {
                    Log.e(RemoteService.TAG, Objects.requireNonNull(e.getMessage()), e);
                }
                mInput.hideCursor();
            } else {
                instance.stop();
                mInput.showCursor();
            }
            return true;
        }
    }

    public MouseEventHandler(IInputInterface mInput) {
        this.mInput = mInput;
    }

    public void init() {
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

    // Consolidated coordinate update – single injection per movement
    private void updatePositionAndInject(int dx, int dy) {
        if (dx != 0) {
            x1 += dx;
            x1 = Math.max(0, Math.min(width, x1));
        }
        if (dy != 0) {
            y1 += dy;
            y1 = Math.max(0, Math.min(height, y1));
        }

        if (pointer_down) {
            mInput.injectEvent(x1, y1, MOVE, pointerId);
        } else {
            mInput.injectHoverEvent(x1, y1, pointerId);
        }

        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler.onCursorPosition(x1, y1);
        }

        mInput.moveCursorX(x1);
        mInput.moveCursorY(y1);
    }

    private void startMouseWalk() {
        if (mouseAimOrCameraHandler != null && mouseAimActive) {
            triggerMouseAimOrCamera(mouseAimOrCameraHandler);
        }
        if (mouseWalkHandler != null) {
            mouseWalkHandler.resetPointer();
            mouseWalkActive = true;
        }
    }

    private void stopMouseWalk() {
        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler.stop();
            mouseWalkActive = false;
        }
    }

    private void handleRightClick(int value) {
        if (value == 1) {
            if (mouseWalkHandler != null) {
                if (mouseWalkActive) {
                    stopMouseWalk();
                } else {
                    startMouseWalk();
                }
            } else if (mInput.getKeymapConfig().rightClickMouseAim) {
                triggerMouseAim();
            }
        } else if (rightClick != null) {
            mInput.injectEvent(rightClick.x, rightClick.y, value, pointerIdRightClick);
        }
    }

    public void handleEvent(int code, int value) {
        if (mouseAimOrCameraHandler != null && mouseAimActive) {
            mouseAimOrCameraHandler.handleEvent(code, value, this::handleMouseEvent);
        } else {
            handleMouseEvent(code, value);
        }
    }

    private void handleMouseEvent(int code, int value) {
        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        if (mInput.getKeyEventHandler().ctrlKeyPressed && pointer_down
                && keymapConfig.ctrlDragMouseGesture) {
            if (pinchZoom != null) pointer_down = pinchZoom.handleEvent(code, value);
            return;
        }

        int dx = 0, dy = 0;

        switch (code) {
            case REL_X:
                dx = (int) (value * sensitivity);
                break;
            case REL_Y:
                dy = (int) (value * sensitivity);
                break;
            case BTN_MOUSE:
                pointer_down = (value == 1);
                if (mInput.getKeyEventHandler().ctrlKeyPressed && keymapConfig.ctrlDragMouseGesture) {
                    pinchZoom = new MousePinchZoom(mInput, x1, y1);
                    pinchZoom.handleEvent(code, value);
                } else {
                    mInput.injectEvent(x1, y1, value, pointerId);
                }
                break;
            case BTN_RIGHT:
                handleRightClick(value);
                break;
            case BTN_EXTRA:
            case BTN_SIDE:
            case BTN_MIDDLE:
                if (value == 1 && Objects.equals(mInput.getKeymapConfig().mouseAimShortcutKey, "KEY_MMB")) {
                    triggerMouseAim();
                }
                break;
            case REL_WHEEL:
                if (scrollZoomHandler != null && mInput.getKeyEventHandler().ctrlKeyPressed
                        && keymapConfig.ctrlMouseWheelZoom) {
                    scrollZoomHandler.onScrollEvent(value, x1, y1);
                } else {
                    int scrollDelta = value * (int) scroll_speed_multiplier;
                    scrollDelta = Math.max(-32, Math.min(32, scrollDelta));
                    mInput.injectScroll(x1, y1, scrollDelta);
                }
                break;
        }

        if (dx != 0 || dy != 0) {
            updatePositionAndInject(dx, dy);
        }
    }

    public void evAbsY(int y) {
        y1 = Math.max(0, Math.min(height, y));
        if (pointer_down) {
            mInput.injectEvent(x1, y1, MOVE, pointerId);
        } else {
            mInput.injectHoverEvent(x1, y1, pointerId);
        }
        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler.onCursorPosition(x1, y1);
        }
        mInput.moveCursorY(y1);
    }

    public void evAbsX(int x) {
        x1 = Math.max(0, Math.min(width, x));
        if (pointer_down) {
            mInput.injectEvent(x1, y1, MOVE, pointerId);
        } else {
            mInput.injectHoverEvent(x1, y1, pointerId);
        }
        if (mouseWalkActive && mouseWalkHandler != null) {
            mouseWalkHandler.onCursorPosition(x1, y1);
        }
        mInput.moveCursorX(x1);
    }

    public void stop() {
        scrollZoomHandler = null;
        pinchZoom = null;
        mouseAimHandler = null;
        mouseCameraHandler = null;
        mouseWalkHandler = null;
    }
}