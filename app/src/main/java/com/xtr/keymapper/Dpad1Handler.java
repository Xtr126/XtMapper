package com.xtr.keymapper;

public class Dpad1Handler {

    private Float radius;
    private Float xOfCenter;
    private Float yOfCenter;
    private final int pointerId = 37;

    public Dpad1Handler(String[] data){
        radius = Float.parseFloat(data[0]);
        xOfCenter = Float.parseFloat(data[1]);
        yOfCenter = Float.parseFloat(data[2]);
    }

    public String getMotionEvent(String key, String event){
        switch (key){
            case "KEY_UP":{

            }
            case "KEY_DOWN":{

            }
            case "KEY_LEFT":{

            }
            case "KEY_RIGHT":{
            
            }
        }
        return "X Y DOWN pointerId\n";
    }
}
