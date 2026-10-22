package xtr.keymapper.keymap.element

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.keymap.KeymapProfileElement

class ShiftKey : KeymapProfileElement {

    @JvmField
    val key1: Key

    @JvmField
    val key2: Key

    @JvmField
    var durationMs: Int = -1

    override fun scale(scaleX: Float, scaleY: Float) {
        key1.x *= scaleX
        key1.y *= scaleY

        key2.x *= scaleX
        key2.y *= scaleY
    }

    constructor(data: Array<String>) : this() {
        key1.code = data[1]
        key1.x = data[2].toFloat()
        key1.y = data[3].toFloat()

        key2.code = data[4]
        key2.x = data[5].toFloat()
        key2.y = data[6].toFloat()

        if (data.size > 7) {
            durationMs = data[7].toIntOrNull() ?: -1
        }
    }

    constructor() {
        key1 = Key()
        key2 = Key()
    }

    protected constructor(parcel: Parcel) {
        key1 = parcel.readParcelable(Key::class.java.classLoader)!!
        key2 = parcel.readParcelable(Key::class.java.classLoader)!!
        durationMs = if (parcel.dataAvail() > 0) parcel.readInt() else -1
    }

    val data: String
        get() {
            var line = TAG + " " +
                    key1.code + " " +
                    key1.x + " " +
                    key1.y + " " +
                    key2.code + " " +
                    key2.x + " " +
                    key2.y
            if (durationMs >= 0) {
                line += " " + durationMs
            }
            return line
        }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(key1, flags)
        dest.writeParcelable(key2, flags)
        dest.writeInt(durationMs)
    }

    companion object {
        const val TAG = "SHIFT_KEY"

        @JvmField
        val CREATOR: Parcelable.Creator<ShiftKey> = object : Parcelable.Creator<ShiftKey> {
            override fun createFromParcel(parcel: Parcel): ShiftKey {
                return ShiftKey(parcel)
            }

            override fun newArray(size: Int): Array<ShiftKey?> {
                return arrayOfNulls(size)
            }
        }
    }
}
