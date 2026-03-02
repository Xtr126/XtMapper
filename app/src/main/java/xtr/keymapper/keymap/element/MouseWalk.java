package xtr.keymapper.keymap.element;

import android.os.Parcel;

import androidx.annotation.NonNull;

import xtr.keymapper.floatingkeys.MovableFrameLayout;
import xtr.keymapper.keymap.KeymapProfileElement;

public final class MouseWalk extends KeymapProfileElement {
    public static final String TAG = "MOUSE_WALK";
    public float x;
    public float y;
    public float radius;

    public MouseWalk(MovableFrameLayout rootView) {
        radius = (rootView.getPivotX() + rootView.getPivotY()) / 2;
        x = rootView.getX() + radius;
        y = rootView.getY() + radius;
    }

    @Override
    public void scale(float scaleX, float scaleY) {
        x *= scaleX;
        y *= scaleY;
        // We cannot decide the exact radius, take the mean of x and y scales
        radius = (radius * (scaleX + scaleY)) / 2;
    }

    public MouseWalk(String[] data) {
        x = Float.parseFloat(data[1]);
        y = Float.parseFloat(data[2]);
        radius = Float.parseFloat(data[3]);
    }

    private MouseWalk(Parcel in) {
        x = in.readFloat();
        y = in.readFloat();
        radius = in.readFloat();
    }

    public static final Creator<MouseWalk> CREATOR = new Creator<>() {
        @Override
        public MouseWalk createFromParcel(Parcel in) {
            return new MouseWalk(in);
        }

        @Override
        public MouseWalk[] newArray(int size) {
            return new MouseWalk[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(x);
        dest.writeFloat(y);
        dest.writeFloat(radius);
    }
}
