package xtr.keymapper.mouse;

import static xtr.keymapper.InputEventCodes.BTN_EXTRA;
import static xtr.keymapper.InputEventCodes.BTN_MIDDLE;
import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.BTN_RIGHT;
import static xtr.keymapper.InputEventCodes.BTN_SIDE;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.touchpointer.PointerId;

public class MouseAimHandler {

    private final MouseAimConfig config;
    private float currentX, currentY;
    private final RectF area = new RectF();
    private IInputInterface service;
    private final int pointerIdMouse = PointerId.pid1.id;
    private final int pointerIdAim = PointerId.pid2.id;
    private final Handler mHandler;

    public MouseAimHandler(MouseAimConfig config){
        currentX = config.xCenter;
        currentY = config.yCenter;
        this.config = config;
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void setInterface(IInputInterface input) {
        this.service = input;
    }

    public void setDimensions(int width, int height){
        if (config.width == 0 || config.height == 0) {
            // Reset pointer if jumping out of screenspace
            area.left = area.top = 0;
            area.right = width;
            area.bottom = height;
        } else {
            // An area around the center point
            area.left = currentX - config.width;
            area.right = currentX + config.width;
            area.top = currentY - config.height;
            area.bottom = currentY + config.height;
        }

    }

    public void resetPointer() {
        service.injectEvent(currentX, currentY, UP, pointerIdAim);
        mHandler.postDelayed(() -> {
                    currentY = config.yCenter;
                    currentX = config.xCenter;
                    service.injectEvent(currentX, currentY, DOWN, pointerIdAim);
                },
                service.getKeymapConfig().swipeDelayMs);
    }

    public void handleEvent(int code, int value, OnButtonClickListener listener) {
        switch (code) {
            case REL_X:
                currentX += calculateScaledX(value);
                if (config.limitedBounds && (currentX > area.right || currentX < area.left))
                    resetPointer();
                service.injectEvent(currentX, currentY, MOVE, pointerIdAim);
                break;
            case REL_Y:
                currentY += calculateScaledY(value);
                if (config.limitedBounds && (currentY > area.bottom || currentY < area.top))
                    resetPointer();
                service.injectEvent(currentX, currentY, MOVE, pointerIdAim);
                break;

            case BTN_MOUSE:
                service.injectEvent(config.xleftClick, config.yleftClick, value, pointerIdMouse);
                break;

            case BTN_SIDE:
            case BTN_MIDDLE:
            case BTN_EXTRA:
            case BTN_RIGHT:
                listener.onButtonClick(code, value);
                break;
        }
    }

    public float calculateScaledX(int value) {
        if (config.applyNonLinearScaling) {
            double dx = config.xCenter - currentX;
            double dy = config.yCenter - currentY;
            double distance = Math.hypot(dx, dy);

            double maxWidth = area.right - area.left;
            double minDistanceToApplyScaling = maxWidth / 20;
            if (distance > minDistanceToApplyScaling) {
                return config.xSensitivity * value * (float)Math.sqrt(minDistanceToApplyScaling / distance);
            } else {
                return value * config.xSensitivity;
            }
        } else if (config.xSensitivity != 1.0) {
            return value * config.xSensitivity;
        } else {
            return value;
        }
    }

    private float calculateScaledY(int value) {
        if (config.ySensitivity != 1.0) {
            return value * config.ySensitivity;
        } else {
            return value;
        }
    }

    public void stop() {
        service.injectEvent(currentX, currentY, UP, pointerIdAim);
    }

    public interface OnButtonClickListener {
        void onButtonClick(int code, int value);
    }
}
