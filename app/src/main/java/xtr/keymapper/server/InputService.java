package xtr.keymapper.server;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.Server;


public class InputService extends Service {
    private final Input input;

    public static final int UP = 0, DOWN = 1, MOVE = 2;

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        new InputService(new Input(), args);
        Looper.loop();
    }

    public InputService(Input input, String[] args) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        super();
        this.input = input;
        Log.i("XtMapper", "starting server...");
        Input.startMouse(Server.DEFAULT_PORT_2);
        Class localClass = Class.forName("android.os.ServiceManager");
        Method addService = localClass.getMethod("addService", String.class, IBinder.class);
        addService.invoke(localClass, "xtmapper", binder);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final IRemoteService.Stub binder = new IRemoteService.Stub() {
        @Override
        public void sendEvent(float x, float y, int type, int pointerId) {
        System.out.println(x + y + pointerId);
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
    };

}