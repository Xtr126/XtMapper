package xtr.keymapper.keymap.element

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.floatingkeys.MovableFrameLayout
import xtr.keymapper.keymap.KeymapProfileElement

class MouseWalk : KeymapProfileElement {

    @JvmField
    var x = 0f

    @JvmField
    var y = 0f

    @JvmField
    var radius = 0f

    constructor(rootView: MovableFrameLayout) {
        radius = (rootView.pivotX + rootView.pivotY) / 2
        x = rootView.x + radius
        y = rootView.y + radius
    }

    constructor(data: Array<String>) {
        x = data[1].toFloat()
        y = data[2].toFloat()
        radius = data[3].toFloat()
    }

    private constructor(parcel: Parcel) {
        x = parcel.readFloat()
        y = parcel.readFloat()
        radius = parcel.readFloat()
    }

    override fun scale(scaleX: Float, scaleY: Float) {
        x *= scaleX
        y *= scaleY
        // We cannot decide the exact radius, take the mean of x and y scales
        radius = (radius * (scaleX + scaleY)) / 2
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeFloat(x)
        dest.writeFloat(y)
        dest.writeFloat(radius)
    }

    companion object {
        const val TAG = "MOUSE_WALK"

        @JvmField
        val CREATOR: Parcelable.Creator<MouseWalk> = object : Parcelable.Creator<MouseWalk> {
            override fun createFromParcel(parcel: Parcel): MouseWalk {
                return MouseWalk(parcel)
            }

            override fun newArray(size: Int): Array<MouseWalk?> {
                return arrayOfNulls(size)
            }
        }
    }
}
