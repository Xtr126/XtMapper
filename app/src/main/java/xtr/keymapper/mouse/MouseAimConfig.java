package xtr.keymapper.mouse;

import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class MouseAimConfig {
    public float xCenter, yCenter, xleftClick, yleftClick;
    public float width, height;
    char triggerKey = '~';
    private static final int initXY = 300;

    public MouseAimConfig() {
        xCenter = xleftClick = yleftClick = yCenter = initXY;
    }

    public MouseAimConfig parse(String[] data){
        xCenter = Float.parseFloat(data[1]);
        yCenter = Float.parseFloat(data[2]);
        triggerKey = data[3].charAt(0);
        width = Float.parseFloat(data[4]);
        height = Float.parseFloat(data[5]);
        xleftClick = Float.parseFloat(data[6]);
        yleftClick = Float.parseFloat(data[7]);
        return this;
    }

    public String getData() {
        return "MOUSE_AIM " + xCenter + " " + yCenter + " "
                + triggerKey + " "
                + width + " " + height + " "
                + xleftClick + " " + yleftClick + "\n";
    }

    public void setCenterXY(MovableFrameLayout crosshair){
        this.xCenter = crosshair.getX();
        this.yCenter = crosshair.getY();
    }

    public void setLeftClickXY(MovableFloatingActionKey leftClick) {
        this.xleftClick = leftClick.getX();
        this.yleftClick = leftClick.getY();
    }
}