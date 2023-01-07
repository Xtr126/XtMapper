package xtr.keymapper.server;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Log;
import android.view.MotionEvent;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.Server;


public class InputService extends Service {
    private static final Input input = new Input();

    public static final int UP = 0, DOWN = 1, MOVE = 2;

    public static void main(String[] args) {
        Looper.prepare();
        new InputService();
        Looper.loop();
    }

    public InputService() {
        super();
        Log.i("XtMapper", "starting server...");
        Input.startMouse(Server.DEFAULT_PORT_2);
        ServiceManager.addService("xtmapper", binder);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final IRemoteService.Stub binder = new IRemoteService.Stub() {
        @Override
        public void injectEvent(float x, float y, int type, int pointerId) {
            System.out.println("receive:" + x + y + pointerId);
            switch (type) {
                case UP:
                    input.injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f, x, y);
                    break;
                case DOWN:
                    input.injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f, x, y);
                    break;
                case MOVE:
                    input.injectTouch(MotionEvent.ACTION_UP, pointerId, 1.0f, x, y);
                    break;
            }
        }
        @Override
        public void injectScroll(float x, float y, int value) {
            input.onScrollEvent(x, y, value);
        }
    };

}