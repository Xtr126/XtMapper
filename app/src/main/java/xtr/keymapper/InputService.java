package xtr.keymapper;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;


public class InputService extends Service {
    private final Input input;

    private static final int UP = 0, DOWN = 1, MOVE = 2;

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        new InputService(new Input());
        Looper.loop();
    }

    public InputService(Input input) {
        super();
        this.input = input;
        Log.i("XtMapper", "starting server...");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final IRemoteService.Stub binder = new IRemoteService.Stub() {
        @Override
        public void sendEvent(float x, float y, int type, int pointerId) {
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