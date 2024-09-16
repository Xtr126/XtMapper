package xtr.keymapper.mouse;

import static xtr.keymapper.mouse.MousePinchZoom.pointerId1;
import static xtr.keymapper.mouse.MousePinchZoom.pointerId2;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;

import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.server.RemoteService;

public class MouseWheelZoom extends HandlerThread {
    private final Handler mHandler;
    private final IInputInterface service;
    private static final int pixels = 50;
    int x1, x2;

    public MouseWheelZoom(IInputInterface service) {
        super("mouse_wheel");
        start();
        mHandler = new Handler(getLooper());
        this.service = service;
    }

    private void initPointers(int x1, int x2, int y) throws RemoteException {
        service.injectEvent(x1, y, DOWN, pointerId1);
        service.injectEvent(x2, y, DOWN, pointerId2);
    }

    public void releasePointers(int x, int y) throws RemoteException {
        service.injectEvent(x, y, UP, pointerId1);
        service.injectEvent(x, y, UP, pointerId2);
    }

    public void onScrollEvent(int value, int x, int y) { mHandler.post(() -> {
        // Shift initial position of two pointers by 50 pixels
        x1 = x + pixels;
        x2 = x - pixels;
        try {
            initPointers(x1, x2, y);
            for (int i = 0; i < 5; i++) {
                x1 += pixels / 5 * value;
                x2 -= pixels / 5 * value;
                service.injectEvent(x1, y, MOVE, pointerId1);
                sleep(10);
                service.injectEvent(x2, y, MOVE, pointerId2);
                sleep(10);
            }
            releasePointers(x, y);
        } catch (RemoteException | InterruptedException e) {
            Log.e(RemoteService.TAG, e.getMessage(), e);
        }
    });
    }
}
