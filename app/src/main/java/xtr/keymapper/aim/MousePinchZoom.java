package xtr.keymapper.aim;

import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import android.os.RemoteException;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.TouchPointer;

public class MousePinchZoom {
    private final IRemoteService service;
    private float currentX1, currentY1;
    private float currentX2, currentY2;
    private final int pointerId1 = TouchPointer.PointerId.pid1.id;
    private final int pointerId2 = TouchPointer.PointerId.pid2.id;

    public MousePinchZoom(IRemoteService service, float initX, float initY) throws RemoteException {
        this.service = service;
        currentX1 = currentX2 = initX;
        currentY1 = currentY2 = initY;
        service.injectEvent(currentX1, currentY1, DOWN, pointerId1);
        service.injectEvent(currentX2, currentY2, DOWN, pointerId2);
    }

    public void releasePointers() throws RemoteException {
        service.injectEvent(currentX1, currentY1, UP, pointerId1);
        service.injectEvent(currentX2, currentY2, UP, pointerId2);
    }

    public void handleEvent(int code, int value) throws RemoteException {
        switch (code) {
            case REL_X:
                currentX1 += value;
                currentX2 -= value;
                service.injectEvent(currentX1, currentY1, MOVE, pointerId1);
                service.injectEvent(currentX2, currentY2, MOVE, pointerId2);
                break;
            case REL_Y:
                currentY1 += value;
                currentY2 -= value;
                service.injectEvent(currentX1, currentY1, MOVE, pointerId1);
                service.injectEvent(currentX2, currentY2, MOVE, pointerId2);
                break;

            case BTN_MOUSE:
                service.injectEvent(currentX1, currentY1, value, pointerId1);
                service.injectEvent(currentX2, currentY2, value, pointerId2);
                break;
        }
    }
}
