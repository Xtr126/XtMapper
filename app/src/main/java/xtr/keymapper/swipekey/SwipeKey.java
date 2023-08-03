package xtr.keymapper.swipekey;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import xtr.keymapper.profiles.KeymapProfileKey;

public class SwipeKey implements Parcelable {
    public KeymapProfileKey key1 = new KeymapProfileKey();
    public KeymapProfileKey key2 = new KeymapProfileKey();

    public static final String type = "SWIPE_KEY";

    public SwipeKey (String[] data){
        key1.code = data[1];
        key1.x = Float.parseFloat(data[2]);
        key1.y = Float.parseFloat(data[3]);

        key2.code = data[4];
        key2.x = Float.parseFloat(data[5]);
        key2.y = Float.parseFloat(data[6]);
    }

    public SwipeKey(SwipeKeyView swipeKey){
        key1.code = swipeKey.button1.getText();
        key1.x = swipeKey.button1.getX();
        key1.y = swipeKey.button1.getY();

        key2.code = swipeKey.button2.getText();
        key2.x = swipeKey.button2.getX();
        key2.y = swipeKey.button2.getY();
    }

    protected SwipeKey(Parcel in) {
        key1 = in.readParcelable(KeymapProfileKey.class.getClassLoader());
        key2 = in.readParcelable(KeymapProfileKey.class.getClassLoader());
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
        return type + " " +
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
