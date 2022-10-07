package com.xtr.keymapper;

import java.io.DataOutputStream;
import java.io.IOException;

public class Dpad1Handler {

    private final String moveUp;
    private final String moveDown;
    private final String moveLeft;
    private final String moveRight;

    private final String moveUpLeft;
    private final String moveUpRight;
    private final String moveDownLeft;
    private final String moveDownRight;

    public Dpad1Handler(String[] data){
        float radius = Float.parseFloat(data[0]);
        float xOfCenter = Float.parseFloat(data[1]);
        float yOfCenter = Float.parseFloat(data[2]);
        int pointerId = 37;

        moveUp = xOfCenter + " " + Float.sum(yOfCenter, -radius) + " MOVE " + pointerId + "\n";
        moveDown = xOfCenter + " " + Float.sum(yOfCenter, radius) + " MOVE " + pointerId + "\n";
        moveLeft = Float.sum(xOfCenter, -radius) + " " + yOfCenter + " MOVE " + pointerId + "\n";
        moveRight = Float.sum(xOfCenter, radius) + " " + yOfCenter + " MOVE " + pointerId + "\n";

        moveUpLeft = Float.sum(xOfCenter, -radius) + " " + Float.sum(yOfCenter, -radius) + " MOVE " + pointerId + "\n";
        moveUpRight = Float.sum(xOfCenter, radius) + " " + Float.sum(yOfCenter, -radius) + " MOVE " + pointerId + "\n";
        moveDownLeft = Float.sum(xOfCenter, -radius) + " " + Float.sum(yOfCenter, radius) + " MOVE " + pointerId + "\n";
        moveDownRight = Float.sum(xOfCenter, radius) + " " + Float.sum(yOfCenter, radius) + " MOVE " + pointerId + "\n";
    }

    public void sendEvent(String key, String event, DataOutputStream xOut) throws IOException {
        boolean pointerDown = event.equals("DOWN");
        switch (key){
            case "KEY_UP":{
                if (pointerDown) xOut.writeBytes(moveUp);
                break;
            }
            case "KEY_DOWN":{
                if (pointerDown) xOut.writeBytes(moveDown);
                break;
            }
            case "KEY_LEFT":{
                if (pointerDown) xOut.writeBytes(moveLeft);
                break;
            }
            case "KEY_RIGHT":{
                if (pointerDown) xOut.writeBytes(moveRight);
                break;
            }
        }
    }
}
