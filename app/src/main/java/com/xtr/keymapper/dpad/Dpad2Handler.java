package com.xtr.keymapper.dpad;

import java.io.DataOutputStream;
import java.io.IOException;

public class Dpad2Handler {

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
    private DataOutputStream xOut;

    public Dpad2Handler(String[] data){
        float radius = Float.parseFloat(data[0]);
        float xOfCenter = Float.parseFloat(data[1]);
        float yOfCenter = Float.parseFloat(data[2]);
        int pointerId = 38;

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

    public void setOutputStream(DataOutputStream xOut){
        this.xOut = xOut;
    }

    public void sendEvent(String key, String event) throws IOException {
        boolean keyDown = event.equals("DOWN");
        if (keyDown) {
            eventDown(key);
        } else {
            eventUp(key);
        }
    }

    private void eventDown(String key) throws IOException {
        switch (key){
            case "KEY_W":{
                if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT)
                    xOut.writeBytes(tapDown);
                KEY_UP = true;

                if (KEY_LEFT) xOut.writeBytes(moveUpLeft);
                else if (KEY_RIGHT) xOut.writeBytes(moveUpRight);
                else xOut.writeBytes(moveUp);
                break;
            }
            case "KEY_S":{
                if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapDown);
                KEY_DOWN = true;

                if (KEY_LEFT) xOut.writeBytes(moveDownLeft);
                else if (KEY_RIGHT) xOut.writeBytes(moveDownRight);
                else xOut.writeBytes(moveDown);
                break;
            }
            case "KEY_A":{
                if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapDown);
                KEY_LEFT = true;

                if (KEY_UP) xOut.writeBytes(moveUpLeft);
                else if (KEY_DOWN) xOut.writeBytes(moveDownLeft);
                else xOut.writeBytes(moveLeft);
                break;
            }
            case "KEY_D":{
                if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                    xOut.writeBytes(tapDown);
                KEY_RIGHT = true;

                if (KEY_UP) xOut.writeBytes(moveUpRight);
                else if (KEY_DOWN) xOut.writeBytes(moveDownRight);
                else xOut.writeBytes(moveRight);
                break;
            }
        }
    }

    private void eventUp(String key) throws IOException {
        switch (key){
            case "KEY_W":{
                if (KEY_LEFT) xOut.writeBytes(moveLeft);
                if (KEY_RIGHT) xOut.writeBytes(moveRight);
                if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT)
                    xOut.writeBytes(tapUp);
                KEY_UP = false;
                break;
            }
            case "KEY_S":{
                if (KEY_LEFT) xOut.writeBytes(moveLeft);
                if (KEY_RIGHT) xOut.writeBytes(moveRight);
                if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapUp);
                KEY_DOWN = false;
                break;
            }
            case "KEY_A":{
                if (KEY_UP) xOut.writeBytes(moveUp);
                if (KEY_DOWN) xOut.writeBytes(moveDown);
                if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapUp);
                KEY_LEFT = false;
                break;
            }
            case "KEY_D":{
                if (KEY_UP) xOut.writeBytes(moveUp);
                if (KEY_DOWN) xOut.writeBytes(moveDown);
                if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                    xOut.writeBytes(tapUp);
                KEY_RIGHT = false;
                break;
            }
        }
    }
}
