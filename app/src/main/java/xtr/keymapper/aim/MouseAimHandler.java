package xtr.keymapper.aim;

import android.graphics.Rect;
import android.graphics.RectF;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;

public class MouseAimHandler {

    private DataOutputStream xOut;
    private final MouseAimKey key;
    private float currentX, currentY;
    private final RectF area = new RectF();
    public boolean active = false;

    public MouseAimHandler(MouseAimKey key){
        currentX = key.x;
        currentY = key.y;
        this.key = key;
    }

    public void setOutputStream(DataOutputStream xOut) {
        this.xOut = xOut;
    }

    public void setDimensions(int width, int height){
        if (key.width == 0) {
            area.left = area.top = 0;
            area.right = width;
            area.bottom = height;
        } else {
            area.left = currentX - key.width;
            area.right = currentX + key.width;
            area.top = currentX - key.height;
            area.bottom = currentX + key.height;
        }
    }

    public void start(BufferedReader in) throws IOException {
        xOut.writeBytes(currentX + " " + currentY + " DOWN 36\n");
        String line;
        while ((line = in.readLine()) != null) {
            String[] input_event = line.split("\\s+");
            switch (input_event[0]) {
                case "REL_X":
                    currentX += Integer.parseInt(input_event[1]);
                    if ( currentX > area.right || currentX < area.left ) {
                        xOut.writeBytes(currentX + " " + currentY + " UP 36\n"); // Release pointer
                        currentX = key.x;
                        xOut.writeBytes(currentX + " " + currentY + " DOWN 36\n");
                    }
                    xOut.writeBytes(currentX + " " + currentY + " " + "MOVE" + " 36" + "\n");
                    break;
                case "REL_Y":
                    currentY += Integer.parseInt(input_event[1]);
                    if ( currentY > area.right || currentY < area.left ) {
                        xOut.writeBytes(currentX + " " + currentY + " UP 36\n");
                        currentY = key.y;
                        xOut.writeBytes(currentX + " " + currentY + " DOWN 36\n");
                    }
                    xOut.writeBytes(currentX + " " + currentY + " " + "MOVE" + " 36" + "\n");
                    break;
            }
            if (!active) break;
        }
    }
}
