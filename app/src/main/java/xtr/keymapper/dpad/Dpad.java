package xtr.keymapper.dpad;

import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class Dpad {
    String type;
    float viewX, viewY;
    float xOfCenter, yOfCenter;
    float radius;

    public enum DpadType {
        // Dpad for up down left right arrow keys
        UDLR ("UDLR_DPAD"),

        // Dpad for W A S D keys
        WASD ("WASD_DPAD");

        DpadType(String s) {
            label = s;
        }
        final String label;
    }

    public Dpad (MovableFrameLayout dpad, DpadType type) {
        viewX = dpad.getX();
        viewY = dpad.getY();
        radius = dpad.getPivotX();
        xOfCenter = viewX + radius;
        yOfCenter = viewY + radius;
        this.type = type.label;
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
