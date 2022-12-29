package xtr.keymapper.aim;

import android.graphics.RectF;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;

import static xtr.keymapper.TouchPointer.PointerId.*;

public class MouseAimHandler {

    private DataOutputStream xOut;
    private final MouseAimConfig config;
    private float currentX, currentY;
    private final RectF area = new RectF();
    public boolean active = false;

    public MouseAimHandler(MouseAimConfig config){
        currentX = config.xCenter;
        currentY = config.yCenter;
        this.config = config;
    }

    public void setOutputStream(DataOutputStream xOut) {
        this.xOut = xOut;
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

    public void start(BufferedReader in) throws IOException {
        final String moveEvent = " MOVE " + pid1.id + "\n";
        final String leftClickEvent = " " + pid2.id + "\n";

        xOut.writeBytes(currentX + " " + currentY + " DOWN 36\n");
        String line;
        while ((line = in.readLine()) != null) {
            String[] input_event = line.split("\\s+");
            switch (input_event[0]) {
                case "REL_X":
                    currentX += Integer.parseInt(input_event[1]);
                    if ( currentX > area.right || currentX < area.left ) {
                        xOut.writeBytes(currentX + " " + currentY + " UP 36\n"); // Release pointer
                        currentX = config.xCenter;
                        xOut.writeBytes(currentX + " " + currentY + " DOWN 36\n");
                    }
                    xOut.writeBytes(currentX + " " + currentY + moveEvent);
                    break;
                case "REL_Y":
                    currentY += Integer.parseInt(input_event[1]);
                    if ( currentY > area.right || currentY < area.left ) {
                        xOut.writeBytes(currentX + " " + currentY + " UP 36\n");
                        currentY = config.yCenter;
                        xOut.writeBytes(currentX + " " + currentY + " DOWN 36\n");
                    }
                    xOut.writeBytes(currentX + " " + currentY + moveEvent);
                    break;

                case "BTN_MOUSE":
                    xOut.writeBytes(config.xleftClick + " " + config.yleftClick + " " + input_event[1] + leftClickEvent);
                    break;

                case "BTN_RIGHT":
                    if(input_event[1].equals("1")) active = false;
            }
            if (!active) break;
        }
    }
}
