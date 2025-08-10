package xtr.keymapper.keymap.element;

import android.os.Parcel;

import androidx.annotation.NonNull;

import xtr.keymapper.dpad.DpadKeyCodes;
import xtr.keymapper.floatingkeys.MovableFrameLayout;
import xtr.keymapper.keymap.KeymapProfileElement;

public class Dpad extends KeymapProfileElement {
    private float viewX, viewY;
    private final int width, height;
    public final float xOfCenter;
    public final float yOfCenter;
    public final float radius;
    public final DpadKeyCodes keycodes;
    public static final String TAG = "DPAD";
    public static final String TAG_ARROW_KEYS = "DPAD_UDLR";
    private final String tag;


    public Dpad (MovableFrameLayout dpad, DpadKeyCodes keycodes, String tag) {
        this.keycodes = keycodes;
        this.tag = tag;
        viewX = dpad.getX();
        viewY = dpad.getY();
        radius = dpad.getPivotX();
        xOfCenter = viewX + radius;
        yOfCenter = viewY + radius;
        width = dpad.getLayoutParams().width;
        height = dpad.getLayoutParams().height;
    }

    protected Dpad(Parcel in) {
        tag = in.readString();
        viewX = in.readFloat();
        viewY = in.readFloat();
        width = in.readInt();
        height = in.readInt();
        xOfCenter = in.readFloat();
        yOfCenter = in.readFloat();
        radius = in.readFloat();
        keycodes = in.readParcelable(DpadKeyCodes.class.getClassLoader());
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

    public String getData(){
        return tag + " " +
                viewX + " " +
                viewY + " " +
                radius + " " +
                xOfCenter + " " +
                yOfCenter + " " +
                width + " " +
                height + " " +
                keycodes.Up + " " +
                keycodes.Down + " " +
                keycodes.Left + " " +
                keycodes.Right;
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

    public void scale(float scaleX, float scaleY) {
        viewX *= scaleX;
        viewY *= scaleY;
    }

    public Dpad(String[] data){
        this.tag = data[0];
        viewX = Float.parseFloat(data[1]); // x y coordinates for use in EditorUI
        viewY = Float.parseFloat(data[2]);
        radius = Float.parseFloat(data[3]); // radius of dpad
        xOfCenter = Float.parseFloat(data[4]); // absolute x position of pivot (center)
        yOfCenter = Float.parseFloat(data[5]); // absolute y position of pivot (center)
        width = Integer.parseInt(data[6]);
        height = Integer.parseInt(data[7]);
        keycodes = new DpadKeyCodes(new String[]{data[8], data[9], data[10], data[11]});
    }

    public int getHeight() {
        return height;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(tag);
        dest.writeFloat(viewX);
        dest.writeFloat(viewY);
        dest.writeInt(width);
        dest.writeInt(height);
        dest.writeFloat(xOfCenter);
        dest.writeFloat(yOfCenter);
        dest.writeFloat(radius);
        dest.writeParcelable(keycodes, flags);
    }
}
