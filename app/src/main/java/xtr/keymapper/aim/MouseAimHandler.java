package xtr.keymapper.aim;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;

public class MouseAimHandler {

    private DataOutputStream xOut;
    private final MouseAimKey key;
    private int width, height;
    private float x1, y1;
    public boolean active = false;

    public MouseAimHandler(MouseAimKey key){
        x1 = key.getX();
        y1 = key.getY();
        this.key = key;
    }

    public void setOutputStream(DataOutputStream xOut) {
        this.xOut = xOut;
    }

    public void setDimensions(int width, int height){
        this.width = width;
        this.height = height;
    }

    public void start(BufferedReader in) throws IOException {
        xOut.writeBytes(x1 + " " + y1 + " DOWN 36\n");
        String line;
        while ((line = in.readLine()) != null) {
            String[] input_event = line.split("\\s+");
            switch (input_event[0]) {
                case "REL_X":
                    x1 += Integer.parseInt(input_event[1]);
                    if ( x1 > width || x1 < 0 ) {
                        xOut.writeBytes(x1 + " " + y1 + " UP 36\n"); // Release pointer
                        x1 = key.getX();
                        xOut.writeBytes(x1 + " " + y1 + " DOWN 36\n");
                    }
                    xOut.writeBytes(x1 + " " + y1 + " " + "MOVE" + " 36" + "\n");
                    break;
                case "REL_Y":
                    y1 += Integer.parseInt(input_event[1]);
                    if ( y1 < 0 || y1 > height ) {
                        xOut.writeBytes(x1 + " " + y1 + " UP 36\n");
                        y1 = key.getY();
                        xOut.writeBytes(x1 + " " + y1 + " DOWN 36\n");
                    }
                    xOut.writeBytes(x1 + " " + y1 + " " + "MOVE" + " 36" + "\n");
                    break;
            }
            if (!active) break;
        }
    }
}
