package xtr.keymapper.keymap;

import android.os.Parcelable;

public abstract class KeymapProfileElement implements Parcelable {

    public abstract void scale(float scaleX, float scaleY);

    public KeymapProfileElement() {}
}
