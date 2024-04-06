package xtr.keymapper.mouse;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class MouseAimConfig implements Parcelable {
    public float xCenter, yCenter, xleftClick, yleftClick;
    public float width, height;
    public boolean limitedBounds = true;
    private static final int initXY = 300;
    public static final String TAG = "MOUSE_AIM";

    public MouseAimConfig() {
        xCenter = xleftClick = yleftClick = yCenter = initXY;
    }

    protected MouseAimConfig(Parcel in) {
        xCenter = in.readFloat();
        yCenter = in.readFloat();
        xleftClick = in.readFloat();
        yleftClick = in.readFloat();
        width = in.readFloat();
        height = in.readFloat();
        limitedBounds = in.readByte() != 0;
    }

    public static final Creator<MouseAimConfig> CREATOR = new Creator<>() {
        @Override
        public MouseAimConfig createFromParcel(Parcel in) {
            return new MouseAimConfig(in);
        }

        @Override
        public MouseAimConfig[] newArray(int size) {
            return new MouseAimConfig[size];
        }
    };

    public MouseAimConfig parse(String[] data){
        xCenter = Float.parseFloat(data[1]);
        yCenter = Float.parseFloat(data[2]);
        limitedBounds = Integer.parseInt(data[3]) != 0;
        width = Float.parseFloat(data[4]);
        height = Float.parseFloat(data[5]);
        xleftClick = Float.parseFloat(data[6]);
        yleftClick = Float.parseFloat(data[7]);
        return this;
    }

    public String getData() {
        return TAG + " " + xCenter + " " + yCenter + " "
                + (limitedBounds ? 1 : 0) + " "
                + width + " " + height + " "
                + xleftClick + " " + yleftClick;
    }

    public void setCenterXY(MovableFrameLayout crosshair){
        this.xCenter = crosshair.getX();
        this.yCenter = crosshair.getY();
    }

    public void setLeftClickXY(MovableFloatingActionKey leftClick) {
        this.xleftClick = leftClick.getX();
        this.yleftClick = leftClick.getY();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(xCenter);
        dest.writeFloat(yCenter);
        dest.writeFloat(xleftClick);
        dest.writeFloat(yleftClick);
        dest.writeFloat(width);
        dest.writeFloat(height);
        dest.writeByte((byte) (limitedBounds ? 1 : 0));
    }
}