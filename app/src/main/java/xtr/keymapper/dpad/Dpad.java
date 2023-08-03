package xtr.keymapper.dpad;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class Dpad implements Parcelable {
    public final String type;
    private final float viewX, viewY;
    private final int width, height;
    final float xOfCenter, yOfCenter;
    final float radius;

    public static final String UDLR = "UDLR_DPAD";
    public static final String WASD = "WASD_DPAD";

    protected Dpad(Parcel in) {
        type = in.readString();
        viewX = in.readFloat();
        viewY = in.readFloat();
        width = in.readInt();
        height = in.readInt();
        xOfCenter = in.readFloat();
        yOfCenter = in.readFloat();
        radius = in.readFloat();
    }

    public static final Creator<Dpad> CREATOR = new Creator<>() {
        @Override
        public Dpad createFromParcel(Parcel in) {
            return new Dpad(in);
        }

        @Override
        public Dpad[] newArray(int size) {
            return new Dpad[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeFloat(viewX);
        dest.writeFloat(viewY);
        dest.writeInt(width);
        dest.writeInt(height);
        dest.writeFloat(xOfCenter);
        dest.writeFloat(yOfCenter);
        dest.writeFloat(radius);
    }

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
