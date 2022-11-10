package com.xtr.keymapper.dpad;

import android.content.Context;

import java.io.DataOutputStream;
import java.io.IOException;

public class DpadHandler {

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

    public DpadHandler(Context context, String[] data){
        DpadConfig dpadConfig = new DpadConfig(context);
        float radius = Float.parseFloat(data[0]) * dpadConfig.getDpadRadiusMultiplier();

        float xOfCenter = Float.parseFloat(data[1]);
        float yOfCenter = Float.parseFloat(data[2]);
        int pointerId = 37; // pointer id 37 is reserved for dpad events

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

    public void handleEvent(String key, String event) throws IOException {
        if (event.equals("DOWN")) {
            sendEventDown(key);
        } else {
            sendEventUp(key);
        }
    }

    private void sendEventDown(String key) throws IOException {
        switch (key){
            case "KEY_UP":
            case "KEY_W": {
                KEY_UP = true;
                if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT) {
                    xOut.writeBytes(tapDown); // Send pointer down event only if no other keys are pressed
                }
                // For moving Dpad in 8 directions with 4 keys
                if (KEY_LEFT)
                    xOut.writeBytes(moveUpLeft);  // If left key is already pressed then move dpad to north-west
                else if (KEY_RIGHT)
                    xOut.writeBytes(moveUpRight); // If right key is already pressed then move dpad to north-east
                else
                    xOut.writeBytes(moveUp); // If left or right keys are not pressed then move dpad straight up
                break;
            }
            case "KEY_DOWN":
            case "KEY_S":{
                KEY_DOWN = true;
                if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapDown);

                if (KEY_LEFT) xOut.writeBytes(moveDownLeft);
                else if (KEY_RIGHT) xOut.writeBytes(moveDownRight);
                else xOut.writeBytes(moveDown);
                break;
            }
            case "KEY_LEFT":
            case "KEY_A":{
                KEY_LEFT = true;
                if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapDown);

                if (KEY_UP) xOut.writeBytes(moveUpLeft);
                else if (KEY_DOWN) xOut.writeBytes(moveDownLeft);
                else xOut.writeBytes(moveLeft);
                break;
            }
            case "KEY_RIGHT":
            case "KEY_D":{
                KEY_RIGHT = true;
                if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                    xOut.writeBytes(tapDown);

                if (KEY_UP) xOut.writeBytes(moveUpRight);
                else if (KEY_DOWN) xOut.writeBytes(moveDownRight);
                else xOut.writeBytes(moveRight);
                break;
            }
        }
    }

    private void sendEventUp(String key) throws IOException {
        switch (key){
            case "KEY_UP":
            case "KEY_W":{
                KEY_UP = false;
                if (!KEY_DOWN && !KEY_LEFT && !KEY_RIGHT)
                    xOut.writeBytes(tapUp);

                if (KEY_LEFT) xOut.writeBytes(moveLeft);
                else if (KEY_RIGHT) xOut.writeBytes(moveRight);
                break;
            }
            case "KEY_DOWN":
            case "KEY_S":{
                KEY_DOWN = false;
                if (!KEY_LEFT && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapUp);

                if (KEY_LEFT) xOut.writeBytes(moveLeft);
                else if (KEY_RIGHT) xOut.writeBytes(moveRight);
                break;
            }
            case "KEY_LEFT":
            case "KEY_A":{
                KEY_LEFT = false;
                if (!KEY_DOWN && !KEY_RIGHT && !KEY_UP)
                    xOut.writeBytes(tapUp);

                if (KEY_UP) xOut.writeBytes(moveUp);
                else if (KEY_DOWN) xOut.writeBytes(moveDown);
                break;
            }
            case "KEY_RIGHT":
            case "KEY_D":{
                KEY_RIGHT = false;
                if (!KEY_DOWN && !KEY_LEFT && !KEY_UP)
                    xOut.writeBytes(tapUp);

                if (KEY_UP) xOut.writeBytes(moveUp);
                else if (KEY_DOWN) xOut.writeBytes(moveDown);
                break;
            }
        }
    }
}