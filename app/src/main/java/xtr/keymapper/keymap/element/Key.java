package xtr.keymapper.keymap.element;

import android.os.Parcel;

import androidx.annotation.NonNull;

import xtr.keymapper.keymap.KeymapProfileElement;

public final class Key extends KeymapProfileElement {
    public String code;
    public float x;
    public float y;
    public final float offset;

    @Override
    public void scale(float scaleX, float scaleY) {
        x *= scaleX;
        y *= scaleY;
    }

    public Key(){

        offset = 0;
    }

    public Key(String[] data) {
        code = data[0];
        x = Float.parseFloat(data[1]);
        y = Float.parseFloat(data[2]);
        offset = Float.parseFloat(data[3]);
    }

    private Key(Parcel in) {
        code = in.readString();
        x = in.readFloat();
        y = in.readFloat();
        offset = in.readFloat();
    }

    public static final Creator<Key> CREATOR = new Creator<>() {
        @Override
        public Key createFromParcel(Parcel in) {
            return new Key(in);
        }

        @Override
        public Key[] newArray(int size) {
            return new Key[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(code);
        dest.writeFloat(x);
        dest.writeFloat(y);
        dest.writeFloat(offset);
    }
}
