package xtr.keymapper.aim;

import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;
import static xtr.keymapper.InputEventCodes.*;


import android.graphics.RectF;
import android.os.RemoteException;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.TouchPointer;

public class MouseAimHandler {

    private final MouseAimConfig config;
    private float currentX, currentY;
    private final RectF area = new RectF();
    public boolean active = false;
    private IRemoteService input;
    private final int pointerId1 = TouchPointer.PointerId.pid1.id;
    private final int pointerId2 = TouchPointer.PointerId.pid2.id;

    public MouseAimHandler(MouseAimConfig config){
        currentX = config.xCenter;
        currentY = config.yCenter;
        this.config = config;
    }

    public void setInterface(IRemoteService input) {
        this.input = input;
    }

    public void setDimensions(int width, int height){
        if (config.width == 0) {
            area.left = area.top = 0;
            area.right = width;
            area.bottom = height;
        } else {
            area.left = currentX - config.width;
            area.right = currentX + config.width;
            area.top = currentY - config.height;
            area.bottom = currentY + config.height;
        }
    }

    public void resetPointer() throws RemoteException {
        currentY = config.yCenter;
        currentX = config.xCenter;
        input.injectEvent(currentX, currentY, UP, pointerId1);
        input.injectEvent(currentX, currentY, DOWN, pointerId1);
    }


    public void handleEvent(int code, int value) throws RemoteException {
        switch (code) {
            case REL_X:
                currentX += value;
                if ( currentX > area.right || currentX < area.left ) resetPointer();
                input.injectEvent(currentX, currentY, MOVE, pointerId1);
                break;
            case REL_Y:
                currentY += value;
                if ( currentY > area.bottom || currentY < area.top ) resetPointer();
                input.injectEvent(currentX, currentY, MOVE, pointerId1);
                break;

            case BTN_MOUSE:
                input.injectEvent(currentX, currentY, value, pointerId2);
                break;

            case BTN_RIGHT:
                if(value == 1) {
                    active = false;
                    input.injectEvent(currentX, currentY, UP, pointerId1);
                }
        }
    }
}
