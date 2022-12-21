package xtr.keymapper.aim;

import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class MouseAimKey {
    public float x, y;
    public float width, height;
    char triggerKey = '~';

    public MouseAimKey() {
    }

    public void setXY(MovableFrameLayout crosshair){
        this.x = crosshair.getX();
        this.y = crosshair.getY();
    }

    public MouseAimKey(String[] data) {
        this.x = Float.parseFloat(data[1]);
        this.y = Float.parseFloat(data[2]);
        triggerKey = data[3].charAt(0);
        width = Float.parseFloat(data[4]);
        height = Float.parseFloat(data[5]);
    }

    public String getData() {
        return "MOUSE_AIM " + x + " " + y + " " + triggerKey + " " + width + " " + height + "\n";
    }
}