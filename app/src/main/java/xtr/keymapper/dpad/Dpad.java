package xtr.keymapper.dpad;

import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class Dpad {
    public final String type;
    private final float viewX, viewY;
    private final int width, height;
    final float xOfCenter, yOfCenter;
    final float radius;

    public static final String UDLR = "UDLR_DPAD";
    public static final String WASD = "WASD_DPAD";

    public enum DpadType {
        // Dpad for up down left right arrow keys
        UDLR (Dpad.UDLR),

        // Dpad for W A S D keys
        WASD (Dpad.WASD);

        DpadType(String s) {
            label = s;
        }
        public final String label;
    }

    public Dpad (MovableFrameLayout dpad, DpadType type) {
        this.type = type.label;
        viewX = dpad.getX();
        viewY = dpad.getY();
        radius = dpad.getPivotX();
        xOfCenter = viewX + radius;
        yOfCenter = viewY + radius;
        width = dpad.getLayoutParams().width;
        height = dpad.getLayoutParams().height;
    }

    public Dpad (String[] data){
        type = data[0];
        viewX = Float.parseFloat(data[1]); // x y coordinates for use in EditorUI
        viewY = Float.parseFloat(data[2]);
        radius = Float.parseFloat(data[3]); // radius of dpad
        xOfCenter = Float.parseFloat(data[4]); // absolute x position of pivot (center)
        yOfCenter = Float.parseFloat(data[5]); // absolute y position of pivot (center)
        width = Integer.parseInt(data[6]);
        height = Integer.parseInt(data[7]);
    }

    public String getData(){
        return type + " " +
                viewX + " " +
                viewY + " " +
                radius + " " +
                xOfCenter + " " +
                yOfCenter + " " +
                width + " " +
                height;
    }

    public float getX() {
        return viewX;
    }
    public float getY() {
        return viewY;
    }
    public int getWidth() {
        return width;
    }
    public int getHeight() {
        return height;
    }
}
