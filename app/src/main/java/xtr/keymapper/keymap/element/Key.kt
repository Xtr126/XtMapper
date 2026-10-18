package xtr.keymapper.keymap.element

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.keymap.KeymapProfileElement

class Key : KeymapProfileElement {

    @JvmField
    var code: String? = null

    @JvmField
    var x = 0f

    @JvmField
    var y = 0f

    @JvmField
    val offset: Float

    override fun scale(scaleX: Float, scaleY: Float) {
        x *= scaleX
        y *= scaleY
    }

    constructor() {
        offset = 0f
    }

    constructor(data: Array<String>) {
        code = data[0]
        x = data[1].toFloat()
        y = data[2].toFloat()
        offset = data[3].toFloat()
    }

    private constructor(parcel: Parcel) {
        code = parcel.readString()
        x = parcel.readFloat()
        y = parcel.readFloat()
        offset = parcel.readFloat()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(code)
        dest.writeFloat(x)
        dest.writeFloat(y)
        dest.writeFloat(offset)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Key> = object : Parcelable.Creator<Key> {
            override fun createFromParcel(parcel: Parcel): Key {
                return Key(parcel)
            }

            override fun newArray(size: Int): Array<Key?> {
                return arrayOfNulls(size)
            }
        }
    }
}
