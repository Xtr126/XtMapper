package xtr.keymapper.swipekey;

import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;

import android.os.Handler;

import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.touchpointer.KeyEventHandler.KeyEvent;
import xtr.keymapper.touchpointer.PidProvider;

public class SwipeKeyHandler {

    private final SwipeEvent swipeEvent1;
    private final SwipeEvent swipeEvent2;
    private final String keycode1;
    private final String keycode2;

    public SwipeKeyHandler(SwipeKey key){
        this.keycode1 = "KEY_" + key.key1.code;
        this.keycode2 = "KEY_" + key.key2.code;
        float midpointX = (key.key1.x + key.key2.x) / 2;
        float midpointY = (key.key1.y + key.key2.y) / 2;
        swipeEvent1 = new SwipeEvent(midpointX, midpointY, key.key1.x, key.key1.y);
        swipeEvent2 = new SwipeEvent(midpointX, midpointY, key.key2.x, key.key2.y);
    }

    private static class SwipeEvent {
        float startX, startY;
        float stopX, stopY;

        public SwipeEvent(float startX, float startY, float stopX, float stopY) {
            this.startX = startX;
            this.startY = startY;
            this.stopX = stopX;
            this.stopY = stopY;
        }
    }

    public void handleEvent(KeyEvent event, IInputInterface service, Handler handler, PidProvider mPid, int swipeDelayMs) {
        SwipeEvent swipeEvent;
        if (event.code.equals(keycode1))
            swipeEvent = swipeEvent1;
        else if (event.code.equals(keycode2))
            swipeEvent = swipeEvent2;
        else return;
        int pid = mPid.getPid(event.code);

        service.injectEvent(swipeEvent.startX, swipeEvent.startY, event.action, pid);

        if (event.action == DOWN) handler.postDelayed(() -> {
            service.injectEvent(swipeEvent.stopX, swipeEvent.stopY, MOVE, pid);
        }, swipeDelayMs);
    }

}
