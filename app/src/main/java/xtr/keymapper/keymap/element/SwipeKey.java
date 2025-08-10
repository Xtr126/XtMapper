package xtr.keymapper.keymap.element;

import android.os.Parcel;

import androidx.annotation.NonNull;

import xtr.keymapper.keymap.KeymapProfileElement;

public class SwipeKey extends KeymapProfileElement {
    public final Key key1;
    public final Key key2;

    public static final String TAG = "SWIPE_KEY";

    @Override
    public void scale(float scaleX, float scaleY) {
        key1.x *= scaleX;
        key1.y *= scaleY;

        key2.x *= scaleX;
        key2.y *= scaleY;

    }

    public SwipeKey(String[] data) {
        this();
        key1.code = data[1];
        key1.x = Float.parseFloat(data[2]);
        key1.y = Float.parseFloat(data[3]);

        key2.code = data[4];
        key2.x = Float.parseFloat(data[5]);
        key2.y = Float.parseFloat(data[6]);
    }

    public SwipeKey() {
        key1 = new Key();
        key2 = new Key();
    }

    protected SwipeKey(Parcel in) {
        key1 = in.readParcelable(Key.class.getClassLoader());
        key2 = in.readParcelable(Key.class.getClassLoader());
    }

    public static final Creator<SwipeKey> CREATOR = new Creator<>() {
        @Override
        public SwipeKey createFromParcel(Parcel in) {
            return new SwipeKey(in);
        }

        @Override
        public SwipeKey[] newArray(int size) {
            return new SwipeKey[size];
        }
    };

    public String getData(){
        return TAG + " " +
                key1.code + " " +
                key1.x + " " +
                key1.y + " " +
                key2.code + " " +
                key2.x + " " +
                key2.y;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(key1, flags);
        dest.writeParcelable(key2, flags);
    }
}
