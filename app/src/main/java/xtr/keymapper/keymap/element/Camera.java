package xtr.keymapper.keymap.element;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import xtr.keymapper.keymap.KeymapProfileElement;

public class Camera extends KeymapProfileElement {
    public float x;
    public float y;
    public char triggerKeyCode = ' ';
    public static final String TAG = "CAMERA";
    public boolean toggle = true;
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
        if (data[6].length() == 5) triggerKeyCode = data[6].charAt(4);
    }

    protected Camera(Parcel in) {
        x = in.readFloat();
        y = in.readFloat();
        triggerKeyCode = (char) in.readInt();
        toggle = in.readByte() != 0;
        xSensitivity = in.readFloat();
        ySensitivity = in.readFloat();
    }

    public static final Creator<Camera> CREATOR = new Creator<Camera>() {
        @Override
        public Camera createFromParcel(Parcel in) {
            return new Camera(in);
        }

        @Override
        public Camera[] newArray(int size) {
            return new Camera[size];
        }
    };

    public String getData() {
        return TAG + " " +
                x + " " +
                y + " " +
                xSensitivity + " " +
                ySensitivity + " " +
                (toggle ? 1 : 0) + " " +
                "KEY_" + triggerKeyCode;
    }



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
        dest.writeInt(triggerKeyCode);
        dest.writeByte((byte) (toggle ? 1 : 0));
        dest.writeFloat(xSensitivity);
        dest.writeFloat(ySensitivity);
    }


}
