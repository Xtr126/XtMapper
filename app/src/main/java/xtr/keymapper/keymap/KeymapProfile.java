package xtr.keymapper.keymap;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import xtr.keymapper.BuildConfig;
import xtr.keymapper.keymap.element.Camera;
import xtr.keymapper.keymap.element.Dpad;
import xtr.keymapper.keymap.element.Key;
import xtr.keymapper.macro.Macro;
import xtr.keymapper.macro.MacroSharedPreferences;
import xtr.keymapper.keymap.element.MouseAimConfig;
import xtr.keymapper.keymap.element.SwipeKey;

public class KeymapProfile implements Parcelable {
    public String packageName = BuildConfig.APPLICATION_ID;
    public final Dpad[] dpadArray;
    public MouseAimConfig mouseAimConfig = null;
    public ArrayList<Key> keys = new ArrayList<>();
    public ArrayList<SwipeKey> swipeKeys = new ArrayList<>();
    public Key rightClick;
    public boolean disabled = false;
    public int xRes, yRes;
    public final HashMap<String, Macro> macroIdMap = new HashMap<>();
    public static final int MAX_DPADS = 3;
    public Camera camera = null;

    public KeymapProfile() {
        dpadArray = new Dpad[MAX_DPADS];
    }

    public void scale(float newWidth, float newHeight) {
        float scaleX = (xRes > 0) ? newWidth / xRes : 1;
        float scaleY = (yRes > 0) ? newHeight / yRes : 1;

        if (xRes > 0 && yRes > 0) {
            Consumer<KeymapProfileElement> scaler = element -> {
                if (element != null)
                    element.scale(scaleX, scaleY);
            };
            keys.forEach(scaler);
            swipeKeys.forEach(scaler);
            for (Dpad dpad : dpadArray) {
                scaler.accept(dpad);
            }
            scaler.accept(rightClick);
            scaler.accept(mouseAimConfig);
        }
    }

    protected KeymapProfile(Parcel in) {
        packageName = in.readString();
        dpadArray = in.createTypedArray(Dpad.CREATOR);
        mouseAimConfig = in.readParcelable(MouseAimConfig.class.getClassLoader());
        keys = in.createTypedArrayList(Key.CREATOR);
        swipeKeys = in.createTypedArrayList(SwipeKey.CREATOR);
        rightClick = in.readParcelable(Key.class.getClassLoader());
        disabled = in.readByte() != 0;
        xRes = in.readInt();
        yRes = in.readInt();
        Map<String, Macro> hashMap = in.readHashMap(getClass().getClassLoader());
        if(hashMap != null) macroIdMap.putAll(hashMap);
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
        dest.writeInt(xRes);
        dest.writeInt(yRes);
        dest.writeMap(macroIdMap);
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

    protected void loadMacros(Context context) {
        MacroSharedPreferences macroSharedPreferences = new MacroSharedPreferences(context);
        macroIdMap.replaceAll((macroId, existingMacro) -> {
            Macro loadedMacro = macroSharedPreferences.getMacro(macroId);
            loadedMacro.triggerKey = existingMacro.triggerKey;
            return loadedMacro;
        });
    }
}
