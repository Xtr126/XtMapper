package com.xtr.keymapper.aim;

import com.xtr.keymapper.floatingkeys.MovableFrameLayout;

public class MouseAimKey {
    float x, y;
    char triggerKey;

    public MouseAimKey(MovableFrameLayout crosshair, String key) {
        this.x = crosshair.getX();
        this.y = crosshair.getY();
        this.triggerKey = key.charAt(0);
    }

    public MouseAimKey(String[] data) {
        this.x = Float.parseFloat(data[1]);
        this.y = Float.parseFloat(data[2]);
        this.triggerKey = data[3].charAt(0);
    }

    public float getX(){
        return x;
    }
    public float getY(){
        return y;
    }

    public String getData() {
        return "MOUSE_AIM " + x + " " + y + " " + triggerKey + "\n";
    }
}