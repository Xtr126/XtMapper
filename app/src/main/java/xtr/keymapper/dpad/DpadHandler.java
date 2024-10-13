package xtr.keymapper.dpad;

import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import android.os.Handler;
import android.os.SystemClock;

import xtr.keymapper.server.IInputInterface;

public class DpadHandler {

    private final DpadEvent moveUp, moveDown, moveLeft, moveRight;

    private final DpadEvent moveUpLeft, moveUpRight, moveDownLeft, moveDownRight;

    private final DpadEvent tapUp, tapDown;

    private boolean KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT;
    private final String KEY_UP_CODE, KEY_DOWN_CODE, KEY_LEFT_CODE, KEY_RIGHT_CODE;

    private IInputInterface input;
    private final int pointerId;
    private final Handler mHandler;
    private final int delayMillis;
    private long lastEvent;

    private static class DpadEvent {
        float x, y;
        int action;

        public DpadEvent(float x, float y, int action) {
            this.x = x;
            this.y = y;
            this.action = action;
        }
    }

    public DpadHandler(Dpad dpad, int pointerId, Handler handler, int delayMillis){
        this.pointerId = pointerId;
        this.mHandler = handler;
        this.delayMillis = delayMillis;

        float radius = dpad.radius;
        float xOfCenter = dpad.xOfCenter;
        float yOfCenter = dpad.yOfCenter;
        
        moveUp = new DpadEvent(xOfCenter, Float.sum(yOfCenter, -radius), MOVE);
        moveDown = new DpadEvent(xOfCenter, Float.sum(yOfCenter, radius), MOVE);
        moveLeft = new DpadEvent(Float.sum(xOfCenter, -radius), yOfCenter, MOVE);
        moveRight = new DpadEvent(Float.sum(xOfCenter, radius), yOfCenter, MOVE);

        moveUpLeft = new DpadEvent(Float.sum(xOfCenter, -radius), Float.sum(yOfCenter, -radius), MOVE);
        moveUpRight = new DpadEvent(Float.sum(xOfCenter, radius), Float.sum(yOfCenter, -radius), MOVE);
        moveDownLeft = new DpadEvent(Float.sum(xOfCenter, -radius), Float.sum(yOfCenter, radius), MOVE);
        moveDownRight = new DpadEvent(Float.sum(xOfCenter, radius), Float.sum(yOfCenter, radius), MOVE);
        
        tapUp = new DpadEvent(xOfCenter, yOfCenter, UP);
        tapDown = new DpadEvent(xOfCenter, yOfCenter, DOWN);

        KEY_UP_CODE = dpad.keycodes.Up;
        KEY_DOWN_CODE = dpad.keycodes.Down;
        KEY_LEFT_CODE = dpad.keycodes.Left;
        KEY_RIGHT_CODE = dpad.keycodes.Right;
    }

    public void setInterface(IInputInterface input){
        this.input = input;
    }

    public void handleEvent(String key, int action) {
        if (action == DOWN) {
            sendEventDown(key);
        } else {
            sendEventUp(key);
        }
    }
    
    private void sendDpadEvent(DpadEvent event) {
        long now = SystemClock.uptimeMillis();

        Runnable sendEvent = () -> {
            input.injectEvent(event.x, event.y, event.action, pointerId);
        };
        if ( (now - lastEvent) >= delayMillis ) mHandler.postDelayed(sendEvent, delayMillis);
        else mHandler.postDelayed(sendEvent, delayMillis + delayMillis);
        lastEvent = now;
    }
    
    private void sendEventDown(String key) {
        if (key.equals(KEY_UP_CODE)) {
            KEY_UP = true;
            if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT) {
                sendDpadEvent(tapDown); // Send pointer down event only if no other keys are pressed
            }
            // For moving Dpad in 8 directions with 4 keys
            if (KEY_LEFT)
                sendDpadEvent(moveUpLeft);  // If left key is already pressed then move dpad to north-west
            else if (KEY_RIGHT)
                sendDpadEvent(moveUpRight); // If right key is already pressed then move dpad to north-east
            else
                sendDpadEvent(moveUp); // If left or right keys are not pressed then move dpad straight up
        }
        else if (key.equals(KEY_DOWN_CODE)) {
            KEY_DOWN = true;
            if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapDown);

            if (KEY_LEFT) sendDpadEvent(moveDownLeft);
            else if (KEY_RIGHT) sendDpadEvent(moveDownRight);
            else sendDpadEvent(moveDown);

        } else if (key.equals(KEY_LEFT_CODE)) {
            KEY_LEFT = true;
            if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapDown);

            if (KEY_UP) sendDpadEvent(moveUpLeft);
            else if (KEY_DOWN) sendDpadEvent(moveDownLeft);
            else sendDpadEvent(moveLeft);

        } else if (key.equals(KEY_RIGHT_CODE)) {
            KEY_RIGHT = true;
            if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                sendDpadEvent(tapDown);

            if (KEY_UP) sendDpadEvent(moveUpRight);
            else if (KEY_DOWN) sendDpadEvent(moveDownRight);
            else sendDpadEvent(moveRight);
        }
    }

    private void sendEventUp(String key) {
        if (key.equals(KEY_UP_CODE)) {
            KEY_UP = false;
            if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT)
                sendDpadEvent(tapUp);

            if (KEY_LEFT) sendDpadEvent(moveLeft);
            else if (KEY_RIGHT) sendDpadEvent(moveRight);

        } else if (key.equals(KEY_DOWN_CODE)) {
            KEY_DOWN = false;
            if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapUp);

            if (KEY_LEFT) sendDpadEvent(moveLeft);
            else if (KEY_RIGHT) sendDpadEvent(moveRight);

        } else if (key.equals(KEY_LEFT_CODE)) {
            KEY_LEFT = false;
            if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                sendDpadEvent(tapUp);

            if (KEY_UP) sendDpadEvent(moveUp);
            else if (KEY_DOWN) sendDpadEvent(moveDown);

        } else if (key.equals(KEY_RIGHT_CODE)) {
            KEY_RIGHT = false;
            if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                sendDpadEvent(tapUp);

            if (KEY_UP) sendDpadEvent(moveUp);
            else if (KEY_DOWN) sendDpadEvent(moveDown);

        }
    }
}