package xtr.keymapper.keymap;

import static xtr.keymapper.dpad.Dpad.MAX_DPADS;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import xtr.keymapper.BuildConfig;
import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.mouse.MouseAimConfig;
import xtr.keymapper.swipekey.SwipeKey;

public class KeymapProfile implements Parcelable {
    public String packageName = BuildConfig.APPLICATION_ID;
    public final Dpad[] dpadArray;
    public MouseAimConfig mouseAimConfig = null;
    public ArrayList<KeymapProfileKey> keys = new ArrayList<>();
    public ArrayList<SwipeKey> swipeKeys = new ArrayList<>();
    public KeymapProfileKey rightClick;
    public boolean disabled = false;
    public Dpad dpadUdlr;
    public int xRes, yRes;
    public final Set<String> macroIds = new HashSet<>();

    public KeymapProfile() {
        dpadArray = new Dpad[MAX_DPADS];
    }

    public void scale(float newWidth, float newHeight) {
        float scaleX = 1, scaleY = 1;

        if (xRes > 0)
            scaleX = newWidth / xRes;

        if (yRes > 0)
            scaleY = newHeight / yRes;

        if (xRes > 0 && yRes > 0) {
            scaleKeys(scaleX, scaleY);
        }
    }

    private void scaleKeys(float scaleX, float scaleY) {
        if (dpadUdlr != null) dpadUdlr.scale(scaleX, scaleY);

        if (rightClick != null) {
            rightClick.x *= scaleX;
            rightClick.y *= scaleY;
        }

        for (SwipeKey swipeKey : swipeKeys) {
            swipeKey.key1.x *= scaleX;
            swipeKey.key1.y *= scaleY;

            swipeKey.key2.x *= scaleX;
            swipeKey.key2.y *= scaleY;
        }

        for (KeymapProfileKey key : keys) {
            key.x *= scaleX;
            key.y *= scaleY;
        }

        for (Dpad dpad : dpadArray) {
            if (dpad != null) dpad.scale(scaleX, scaleY);
        }
        if (mouseAimConfig != null) {
            mouseAimConfig.xCenter *= scaleX;
            mouseAimConfig.yCenter *= scaleY;

            mouseAimConfig.xleftClick *= scaleX;
            mouseAimConfig.yleftClick *= scaleY;

            mouseAimConfig.width *= scaleX;
            mouseAimConfig.height *= scaleY;
        }
    }

    protected KeymapProfile(Parcel in) {
        packageName = in.readString();
        dpadArray = in.createTypedArray(Dpad.CREATOR);
        mouseAimConfig = in.readParcelable(MouseAimConfig.class.getClassLoader());
        keys = in.createTypedArrayList(KeymapProfileKey.CREATOR);
        swipeKeys = in.createTypedArrayList(SwipeKey.CREATOR);
        rightClick = in.readParcelable(KeymapProfileKey.class.getClassLoader());
        disabled = in.readByte() != 0;
        dpadUdlr = in.readParcelable(Dpad.class.getClassLoader());
        xRes = in.readInt();
        yRes = in.readInt();
        ArrayList<String> stringArrayList = in.createStringArrayList();
        macroIds.clear();
        if (stringArrayList != null) {
            macroIds.addAll(stringArrayList);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeTypedArray(dpadArray, flags);
        dest.writeParcelable(mouseAimConfig, flags);
        dest.writeTypedList(keys);
        dest.writeTypedList(swipeKeys);
        dest.writeParcelable(rightClick, flags);
        dest.writeByte((byte) (disabled ? 1 : 0));
        dest.writeParcelable(dpadUdlr, flags);
        dest.writeInt(xRes);
        dest.writeInt(yRes);
        dest.writeStringList(List.copyOf(macroIds));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<KeymapProfile> CREATOR = new Creator<>() {
        @Override
        public KeymapProfile createFromParcel(Parcel in) {
            return new KeymapProfile(in);
        }

        @Override
        public KeymapProfile[] newArray(int size) {
            return new KeymapProfile[size];
        }
    };

}
