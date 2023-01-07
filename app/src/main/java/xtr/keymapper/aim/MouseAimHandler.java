package xtr.keymapper.aim;

import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import android.graphics.RectF;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.IOException;

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
            area.top = currentX - config.height;
            area.bottom = currentX + config.height;
        }
    }

    private void resetPointer() throws RemoteException {
        currentY = config.yCenter;
        currentX = config.xCenter;
        input.injectEvent(currentX, currentY, UP, pointerId1);
        input.injectEvent(currentX, currentY, DOWN, pointerId1);
    }

    public static class MouseEvent {
        public String code;
        public int value;
        public MouseEvent(String line) {
            String[] data = line.split("\\s+");
            this.code = data[0];
            this.value = Integer.parseInt(data[1]);
        }
    }

    public void start(BufferedReader in) throws IOException, RemoteException {

        input.injectEvent(currentX, currentY, DOWN, pointerId1);
        String line;
        while ((line = in.readLine()) != null) {
            MouseEvent event = new MouseEvent(line);
            switch (event.code) {
                case "REL_X":
                    currentX += event.value;
                    if ( currentX > area.right || currentX < area.left ) resetPointer();
                    input.injectEvent(currentX, currentY, MOVE, pointerId1);
                    break;
                case "REL_Y":
                    currentY += event.value;
                    if ( currentY > area.right || currentY < area.left ) resetPointer();
                    input.injectEvent(currentX, currentY, MOVE, pointerId1);
                    break;

                case "BTN_MOUSE":
                    input.injectEvent(currentX, currentY, event.value, pointerId2);
                    break;

                case "BTN_RIGHT":
                    if(event.value == 1) active = false;
            }
            if (!active) break;
        }
    }
}
