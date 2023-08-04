package xtr.keymapper.keymap;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public final class KeymapProfileKey implements Parcelable {
    public String code;
    public float x;
    public float y;
    public float offset;

    public KeymapProfileKey(){

    }

    protected KeymapProfileKey(Parcel in) {
        code = in.readString();
        x = in.readFloat();
        y = in.readFloat();
        offset = in.readFloat();
    }

    public static final Creator<KeymapProfileKey> CREATOR = new Creator<>() {
        @Override
        public KeymapProfileKey createFromParcel(Parcel in) {
            return new KeymapProfileKey(in);
        }

        @Override
        public KeymapProfileKey[] newArray(int size) {
            return new KeymapProfileKey[size];
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
