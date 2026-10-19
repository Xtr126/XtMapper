package xtr.keymapper.keymap.element

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.keymap.KeymapProfileElement

class Camera : KeymapProfileElement {

    @JvmField
    var x = 0f

    @JvmField
    var y = 0f

    @JvmField
    var triggerKeyCode = "KEY_ALT"

    @JvmField
    var toggle = true

    @JvmField
    var xSensitivity = 1f

    @JvmField
    var ySensitivity = 1f

    constructor()

    constructor(data: Array<String>) {
        x = data[1].toFloat()
        y = data[2].toFloat()
        xSensitivity = data[3].toFloat()
        ySensitivity = data[4].toFloat()
        toggle = data[5].toInt() != 0
        if (data[6].length >= 5) triggerKeyCode = data[6]
    }

    protected constructor(parcel: Parcel) {
        x = parcel.readFloat()
        y = parcel.readFloat()
        triggerKeyCode = parcel.readString()!!
        toggle = parcel.readByte().toInt() != 0
        xSensitivity = parcel.readFloat()
        ySensitivity = parcel.readFloat()
    }

    val data: String
        get() {
        return TAG + " " +
                x + " " +
                y + " " +
                xSensitivity + " " +
                ySensitivity + " " +
                (if (toggle) 1 else 0) + " " +
                "KEY_" + triggerKeyCode
    }

    override fun scale(scaleX: Float, scaleY: Float) {
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeFloat(x)
        dest.writeFloat(y)
        dest.writeString(triggerKeyCode)
        dest.writeByte((if (toggle) 1 else 0).toByte())
        dest.writeFloat(xSensitivity)
        dest.writeFloat(ySensitivity)
    }

    companion object {
        const val TAG = "CAMERA"

        @JvmField
        val CREATOR: Parcelable.Creator<Camera> = object : Parcelable.Creator<Camera> {
            override fun createFromParcel(parcel: Parcel): Camera {
                return Camera(parcel)
            }

            override fun newArray(size: Int): Array<Camera?> {
                return arrayOfNulls(size)
            }
        }
    }
}
