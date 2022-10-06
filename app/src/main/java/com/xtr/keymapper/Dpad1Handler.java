package com.xtr.keymapper;

import java.io.DataOutputStream;
import java.io.IOException;

public class Dpad1Handler {

    private final Float radius;
    private final Float xOfCenter;
    private final Float yOfCenter;
    private final int pointerId = 37;
    private final String moveUp;
    private final String moveDown;
    private final String moveLeft;
    private final String moveRight;

    public Dpad1Handler(String[] data){
        radius = Float.parseFloat(data[0]);
        xOfCenter = Float.parseFloat(data[1]);
        yOfCenter = Float.parseFloat(data[2]);

        moveUp = xOfCenter + " " + Float.sum(yOfCenter, -radius) + " MOVE " + pointerId + "\n";
        moveDown = xOfCenter + " " + Float.sum(yOfCenter, radius) + " MOVE " + pointerId + "\n";
        moveLeft = Float.sum(xOfCenter, -radius) + " " + yOfCenter + " MOVE " + pointerId + "\n";
        moveRight = Float.sum(xOfCenter, radius) + " " + yOfCenter + " MOVE " + pointerId + "\n";
    }

    public void sendEvent(String key, String event, DataOutputStream xOut) throws IOException {
        boolean pointerDown = event.equals("DOWN");
        switch (key){
            case "KEY_UP":{
                if (pointerDown)
                xOut.writeBytes(moveUp);
                break;
            }
            case "KEY_DOWN":{
                if (pointerDown)
                xOut.writeBytes(moveDown);
                break;
            }
            case "KEY_LEFT":{
                if (pointerDown)
                xOut.writeBytes(moveLeft);
                break;
            }
            case "KEY_RIGHT":{
                if (pointerDown)
                xOut.writeBytes(moveRight);
                break;
            }
        }
    }
}
