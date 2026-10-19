package xtr.keymapper.dpad

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.databinding.DpadBinding

class DpadKeyCodes : Parcelable {

    @JvmField
    val Up: String

    @JvmField
    val Down: String

    @JvmField
    val Left: String

    @JvmField
    val Right: String

    constructor(strings: Array<String>) {
        Up = strings[0]
        Down = strings[1]
        Left = strings[2]
        Right = strings[3]
    }

    constructor(binding: DpadBinding) {
        Up = "KEY_" + binding.keyUp.getText()
        Down = "KEY_" + binding.keyDown.getText()
        Left = "KEY_" + binding.keyLeft.getText()
        Right = "KEY_" + binding.keyRight.getText()
    }

    protected constructor(parcel: Parcel) {
        Up = parcel.readString()!!
        Down = parcel.readString()!!
        Left = parcel.readString()!!
        Right = parcel.readString()!!
    }

    // Overload, not an override: Java callers pass DpadKeyCodes explicitly
    fun equals(keyCodes: DpadKeyCodes): Boolean {
        return Up == keyCodes.Up &&
                Down == keyCodes.Down &&
                Left == keyCodes.Left &&
                Right == keyCodes.Right
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(Up)
        dest.writeString(Down)
        dest.writeString(Left)
        dest.writeString(Right)
    }

    companion object {
        @JvmField
        val ARROW_KEYS = DpadKeyCodes(arrayOf("KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT"))

        @JvmField
        val WASD_KEYS = DpadKeyCodes(arrayOf("KEY_W", "KEY_S", "KEY_A", "KEY_D"))

        @JvmField
        val IJKL_KEYS = DpadKeyCodes(arrayOf("KEY_I", "KEY_K", "KEY_J", "KEY_L"))

        @JvmField
        val CREATOR: Parcelable.Creator<DpadKeyCodes> = object : Parcelable.Creator<DpadKeyCodes> {
            override fun createFromParcel(parcel: Parcel): DpadKeyCodes {
                return DpadKeyCodes(parcel)
            }

            override fun newArray(size: Int): Array<DpadKeyCodes?> {
                return arrayOfNulls(size)
            }
        }
    }
}
