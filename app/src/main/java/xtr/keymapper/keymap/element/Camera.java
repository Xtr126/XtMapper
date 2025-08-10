package xtr.keymapper.keymap.element;

import android.os.Parcel;

import androidx.annotation.NonNull;

import xtr.keymapper.keymap.KeymapProfileElement;

public class Camera extends KeymapProfileElement {
    public float x;
    public float y;
    public int triggerKeyCode = -1;
    public static final String TAG = "CAMERA";
    public boolean toggle;
    public float xSensitivity = 1f;
    public float ySensitivity = 1f;

    public Camera() {

    }

    public Camera(String[] data){
        x = Float.parseFloat(data[1]);
        y = Float.parseFloat(data[2]);
        xSensitivity = Float.parseFloat(data[3]);
        ySensitivity = Float.parseFloat(data[4]);
        toggle = Integer.parseInt(data[5]) != 0;
        triggerKeyCode = Integer.parseInt(data[6]);
    }

    public String getData() {
        return TAG + " " +
                x + " " +
                y + " " +
                xSensitivity + " " +
                ySensitivity + " " +
                (toggle ? 1 : 0) + " " +
                triggerKeyCode;
    }

    protected Camera(Parcel in) {
        x = in.readFloat();
        y = in.readFloat();
        xSensitivity = in.readFloat();
        ySensitivity = in.readFloat();
        toggle = in.readByte() != 0;
        triggerKeyCode = in.readInt();
    }


    public static final Creator<Camera> CREATOR = new Creator<>() {
        @Override
        public Camera createFromParcel(Parcel in) {
            return new Camera(in);
        }

        @Override
        public Camera[] newArray(int size) {
            return new Camera[size];
        }
    };

    @Override
    public void scale(float scaleX, float scaleY) {

    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(x);
        dest.writeFloat(y);
        dest.writeFloat(xSensitivity);
        dest.writeFloat(ySensitivity);
        dest.writeByte((byte) (toggle ? 1 : 0));
        dest.writeInt(triggerKeyCode);
    }

}
