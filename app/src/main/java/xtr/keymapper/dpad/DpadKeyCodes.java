package xtr.keymapper.dpad;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import xtr.keymapper.databinding.DpadWasdBinding;

public class DpadKeyCodes implements Parcelable {
    public final String Up, Down, Left, Right;

    DpadKeyCodes(String[] in){
        Up = in[0];
        Down = in[1];
        Left = in[2];
        Right = in[3];
    }

    public DpadKeyCodes(DpadWasdBinding binding){
        Up = "KEY_" + binding.keyUp.getText();
        Down = "KEY_" + binding.keyDown.getText();
        Left = "KEY_" + binding.keyLeft.getText();
        Right = "KEY_" + binding.keyRight.getText();
    }

    protected DpadKeyCodes(Parcel in) {
        Up = in.readString();
        Down = in.readString();
        Left = in.readString();
        Right = in.readString();
    }

    public static final Creator<DpadKeyCodes> CREATOR = new Creator<>() {
        @Override
        public DpadKeyCodes createFromParcel(Parcel in) {
            return new DpadKeyCodes(in);
        }

        @Override
        public DpadKeyCodes[] newArray(int size) {
            return new DpadKeyCodes[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(Up);
        dest.writeString(Down);
        dest.writeString(Left);
        dest.writeString(Right);
    }
}
