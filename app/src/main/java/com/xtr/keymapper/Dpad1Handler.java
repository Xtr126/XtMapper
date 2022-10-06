package com.xtr.keymapper;

import java.io.DataOutputStream;
import java.io.IOException;

public class Dpad1Handler {

    private final Float radius;
    private final Float xOfCenter;
    private final Float yOfCenter;

    public Dpad1Handler(String[] data){
        radius = Float.parseFloat(data[0]);
        xOfCenter = Float.parseFloat(data[1]);
        yOfCenter = Float.parseFloat(data[2]);
    }

    public void sendEvent(String key, String event, DataOutputStream xOut) throws IOException {
        int pointerId = 37;
        switch (key){
            case "KEY_UP":{

                xOut.writeBytes(xOfCenter + " " + Float.sum(yOfCenter, -radius) + " MOVE " + pointerId + "\n");
                break;
            }
            case "KEY_DOWN":{
                xOut.writeBytes(xOfCenter + " " + Float.sum(yOfCenter, radius) + " MOVE " + pointerId + "\n");
                break;
            }
            case "KEY_LEFT":{
                xOut.writeBytes(Float.sum(xOfCenter, -radius) + " " + yOfCenter + " MOVE " + pointerId + "\n");
                break;
            }
            case "KEY_RIGHT":{
                xOut.writeBytes(Float.sum(xOfCenter, radius) + " " + yOfCenter + " MOVE " + pointerId + "\n");
                break;
            }
        }
    }
}
