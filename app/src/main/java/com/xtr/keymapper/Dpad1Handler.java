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
    private final String tapUp;
    private final String tapDown;

    private boolean KEY_UP;
    private boolean KEY_DOWN;
    private boolean KEY_LEFT;
    private boolean KEY_RIGHT;

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

        tapUp = xOfCenter + " " + yOfCenter + " UP " + pointerId + "\n";
        tapDown = xOfCenter + " " + yOfCenter + " DOWN " + pointerId + "\n";
    }

    public void sendEvent(String key, String event, DataOutputStream xOut) throws IOException {
        boolean pointerDown = event.equals("DOWN");
        switch (key){
            case "KEY_UP":{
                if (pointerDown) {
                    xOut.writeBytes(tapDown);
                    KEY_UP = true;
                } else {
                    xOut.writeBytes(tapUp);
                    KEY_UP = false;
                }
                xOut.writeBytes(moveUp);
                break;
            }
            case "KEY_DOWN":{
                if (pointerDown) {
                    xOut.writeBytes(tapDown);
                    KEY_DOWN = true;
                } else {
                    xOut.writeBytes(tapUp);
                    KEY_DOWN = false;
                }
                xOut.writeBytes(moveDown);
                break;
            }
            case "KEY_LEFT":{
                if (pointerDown) {
                    xOut.writeBytes(tapDown);
                    KEY_LEFT = true;
                } else {
                    xOut.writeBytes(tapUp);
                    KEY_LEFT = false;
                }
                xOut.writeBytes(moveLeft);
                break;
            }
            case "KEY_RIGHT":{
                if (pointerDown) {
                    xOut.writeBytes(tapDown);
                    KEY_RIGHT = true;
                } else {
                    xOut.writeBytes(tapUp);
                    KEY_RIGHT = false;
                }
                xOut.writeBytes(moveRight);
                break;
            }
        }
    }
}
