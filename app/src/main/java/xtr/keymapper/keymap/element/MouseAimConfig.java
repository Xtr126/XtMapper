package xtr.keymapper.keymap.element;

import android.os.Parcel;

import androidx.annotation.NonNull;

import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.floatingkeys.MovableFrameLayout;
import xtr.keymapper.keymap.KeymapProfileElement;

public class MouseAimConfig extends KeymapProfileElement {
    public float xCenter, yCenter, xleftClick, yleftClick;
    public float width, height;
    public boolean limitedBounds = true;
    private static final int initXY = 300;
    public static final String TAG = "MOUSE_AIM";
    public float xSensitivity = 1, ySensitivity = 1;
    public boolean applyNonLinearScaling = false;

    @Override
    public void scale(float scaleX, float scaleY) {
        xCenter *= scaleX;
        yCenter *= scaleY;

        xleftClick *= scaleX;
        yleftClick *= scaleY;

        width *= scaleX;
        height *= scaleY;

    }

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
        xSensitivity = in.readFloat();
        ySensitivity = in.readFloat();
        applyNonLinearScaling = in.readByte() != 0;
    }

    public MouseAimConfig(float xCenter, float yCenter,
                  float xleftClick, float yleftClick,
                  float width, float height,
                  boolean limitedBounds,
                  float xSensitivity, float ySensitivity,
                  boolean applyNonLinearScaling) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.xleftClick = xleftClick;
        this.yleftClick = yleftClick;
        this.width = width;
        this.height = height;
        this.limitedBounds = limitedBounds;
        this.xSensitivity = xSensitivity;
        this.ySensitivity = ySensitivity;
        this.applyNonLinearScaling = applyNonLinearScaling;
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

    public MouseAimConfig(String[] data){
        xCenter = Float.parseFloat(data[1]);
        yCenter = Float.parseFloat(data[2]);
        limitedBounds = Integer.parseInt(data[3]) != 0;
        width = Float.parseFloat(data[4]);
        height = Float.parseFloat(data[5]);
        xleftClick = Float.parseFloat(data[6]);
        yleftClick = Float.parseFloat(data[7]);
        if (data.length == 11) {
            xSensitivity = Float.parseFloat(data[8]);
            ySensitivity = Float.parseFloat(data[9]);
            applyNonLinearScaling = Integer.parseInt(data[10]) != 0;
        }
    }

    public String getData() {
        return TAG + " " + xCenter + " " + yCenter + " "
                + (limitedBounds ? 1 : 0) + " "
                + width + " " + height + " "
                + xleftClick + " " + yleftClick + " "
                + xSensitivity + " " + ySensitivity + " "
                + (applyNonLinearScaling ? 1 : 0);
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
        dest.writeFloat(xSensitivity);
        dest.writeFloat(ySensitivity);
        dest.writeByte((byte) (applyNonLinearScaling ? 1 : 0));
    }
}