package xtr.keymapper.dpad;

import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class Dpad {
    String type;
    float viewX, viewY;
    float xOfCenter, yOfCenter;
    float radius;
    public static final int TYPE_UDLR = 1, TYPE_WASD = 2;

    public Dpad (MovableFrameLayout dpad, int type) {
        viewX = dpad.getX();
        viewY = dpad.getY();
        radius = dpad.getPivotX();
        xOfCenter = viewX + radius;
        yOfCenter = viewY + radius;

        switch (type) {
            case TYPE_UDLR:
                this.type = "UDLR_DPAD";
                break;
            case TYPE_WASD:
                this.type = "WASD_DPAD";
                break;
        }
    }

    public Dpad (String[] data){
        type = data[0];
        viewX = Float.parseFloat(data[1]); // x y coordinates for use in EditorUI
        viewY = Float.parseFloat(data[2]);
        radius = Float.parseFloat(data[3]); // radius of dpad
        xOfCenter = Float.parseFloat(data[4]); // absolute x position of pivot (center)
        yOfCenter = Float.parseFloat(data[5]); // absolute y position of pivot (center)
    }

    public StringBuilder getData(){
        StringBuilder data = new StringBuilder();
        data.append(type).append(" ")
            .append(viewX).append(" ")
            .append(viewY).append(" ")
            .append(radius).append(" ")
            .append(xOfCenter).append(" ")
            .append(yOfCenter).append("\n");
        return data;
    }

    public float getX() {
        return viewX;
    }
    public float getY() {
        return viewY;
    }
}
