package xtr.keymapper.mouse;

import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.touchpointer.PointerId;

public class MousePinchZoom {
    private final IInputInterface service;
    private float currentX1, currentY1;
    private float currentX2, currentY2;
    private final float centerX, centerY;
    static final int pointerId1 = PointerId.pid1.id;
    static final int pointerId2 = PointerId.pid2.id;
    private static final int pixels = 50;

    public MousePinchZoom(IInputInterface service, float initX, float initY) {
        this.service = service;
        centerX = initX;
        centerY = initY;

        // Shift initial position of two pointers by 50 pixels
        currentX1 = initX + pixels; currentY1 = initY + pixels;
        currentX2 = initX - pixels; currentY2 = initY - pixels;
    }

    private void initPointers() {
        service.injectEvent(currentX1, currentY1, DOWN, pointerId1);
        service.injectEvent(currentX2, currentY2, DOWN, pointerId2);
    }

    public void releasePointers() {
        service.injectEvent(currentX1, currentY1, UP, pointerId1);
        service.injectEvent(currentX2, currentY2, UP, pointerId2);
    }

    /*
     * Move position of pointers away from center
     * To make space for performing zoom out gesture
     */
    private void moveAwayPointers() {
        releasePointers();
        currentX1 += 100; currentX2 -= 100;
        currentY1 += 100; currentY2 -= 100;
        initPointers();
    }

    public boolean handleEvent(int code, int value) {
        switch (code) {
            case REL_X:
                currentX1 += value;
                currentX2 -= value;
                // If it passed through the center in opposite direction
                if (centerX > currentX1) moveAwayPointers();

                service.injectEvent(currentX1, currentY1, MOVE, pointerId1);
                service.injectEvent(currentX2, currentY2, MOVE, pointerId2);
                break;
            case REL_Y:
                currentY1 += value;
                currentY2 -= value;
                if (centerY > currentY1) moveAwayPointers();

                service.injectEvent(currentX1, currentY1, MOVE, pointerId1);
                service.injectEvent(currentX2, currentY2, MOVE, pointerId2);
                break;

            case BTN_MOUSE:
                service.injectEvent(currentX1, currentY1, value, pointerId1);
                service.injectEvent(currentX2, currentY2, value, pointerId2);
                if (value == UP) return false;
                break;
        }
        return true;
    }
}
