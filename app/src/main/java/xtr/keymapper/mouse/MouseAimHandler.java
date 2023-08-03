package xtr.keymapper.mouse;

import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.BTN_RIGHT;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.UP;
import static xtr.keymapper.server.InputService.MOVE;

import android.graphics.RectF;
import android.os.RemoteException;

import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.touchpointer.PointerId;

public class MouseAimHandler {

    private final MouseAimConfig config;
    private float currentX, currentY;
    private final RectF area = new RectF();
    public boolean active = false;
    private IInputInterface service;
    private final int pointerId1 = PointerId.pid1.id;
    private final int pointerId2 = PointerId.pid2.id;

    public MouseAimHandler(MouseAimConfig config){
        currentX = config.xCenter;
        currentY = config.yCenter;
        this.config = config;
    }

    public void setInterface(IInputInterface input) {
        this.service = input;
    }

    public void setDimensions(int width, int height){
        if (config.width == 0) {
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
        currentY = config.yCenter;
        currentX = config.xCenter;
        service.injectEvent(currentX, currentY, UP, pointerId1);
        service.injectEvent(currentX, currentY, DOWN, pointerId1);
    }


    public void handleEvent(int code, int value, OnRightClick r) throws RemoteException {
        switch (code) {
            case REL_X:
                currentX += value;
                if ( currentX > area.right || currentX < area.left ) resetPointer();
                service.injectEvent(currentX, currentY, MOVE, pointerId1);
                break;
            case REL_Y:
                currentY += value;
                if ( currentY > area.bottom || currentY < area.top ) resetPointer();
                service.injectEvent(currentX, currentY, MOVE, pointerId1);
                break;

            case BTN_MOUSE:
                service.injectEvent(config.xleftClick, config.yleftClick, value, pointerId2);
                break;

            case BTN_RIGHT:
                r.onRightClick(value);
                break;
        }
    }

    public interface OnRightClick {
        void onRightClick(int value) throws RemoteException;
    }
}
