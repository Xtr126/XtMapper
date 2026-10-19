package xtr.keymapper.keymap.element

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.dpad.DpadKeyCodes
import xtr.keymapper.floatingkeys.MovableFrameLayout
import xtr.keymapper.keymap.KeymapProfileElement

class Dpad : KeymapProfileElement {

    private var viewX = 0f
    private var viewY = 0f
    private val width: Int
    private val height: Int

    @JvmField
    val xOfCenter: Float

    @JvmField
    val yOfCenter: Float

    @JvmField
    val radius: Float

    @JvmField
    val keycodes: DpadKeyCodes

    private val tag: String

    constructor(dpad: MovableFrameLayout, keycodes: DpadKeyCodes, tag: String) {
        this.keycodes = keycodes
        this.tag = tag
        viewX = dpad.x
        viewY = dpad.y
        radius = dpad.pivotX
        xOfCenter = viewX + radius
        yOfCenter = viewY + radius
        width = dpad.layoutParams.width
        height = dpad.layoutParams.height
    }

    constructor(data: Array<String>) {
        tag = data[0]
        viewX = data[1].toFloat() // x y coordinates for use in EditorUI
        viewY = data[2].toFloat()
        radius = data[3].toFloat() // radius of dpad
        xOfCenter = data[4].toFloat() // absolute x position of pivot (center)
        yOfCenter = data[5].toFloat() // absolute y position of pivot (center)
        width = data[6].toInt()
        height = data[7].toInt()
        keycodes = DpadKeyCodes(arrayOf(data[8], data[9], data[10], data[11]))
    }

    private constructor(parcel: Parcel) {
        tag = parcel.readString()!!
        viewX = parcel.readFloat()
        viewY = parcel.readFloat()
        width = parcel.readInt()
        height = parcel.readInt()
        xOfCenter = parcel.readFloat()
        yOfCenter = parcel.readFloat()
        radius = parcel.readFloat()
        keycodes = parcel.readParcelable(DpadKeyCodes::class.java.classLoader)!!
    }

    val data: String
        get() {
        return tag + " " +
                viewX + " " +
                viewY + " " +
                radius + " " +
                xOfCenter + " " +
                yOfCenter + " " +
                width + " " +
                height + " " +
                keycodes.Up + " " +
                keycodes.Down + " " +
                keycodes.Left + " " +
                keycodes.Right
    }

    fun getX(): Float {
        return viewX
    }

    fun getY(): Float {
        return viewY
    }

    fun getWidth(): Int {
        return width
    }

    override fun scale(scaleX: Float, scaleY: Float) {
        viewX *= scaleX
        viewY *= scaleY
    }

    fun getHeight(): Int {
        return height
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(tag)
        dest.writeFloat(viewX)
        dest.writeFloat(viewY)
        dest.writeInt(width)
        dest.writeInt(height)
        dest.writeFloat(xOfCenter)
        dest.writeFloat(yOfCenter)
        dest.writeFloat(radius)
        dest.writeParcelable(keycodes, flags)
    }

    companion object {
        const val TAG = "DPAD"
        const val TAG_ARROW_KEYS = "DPAD_UDLR"

        @JvmField
        val CREATOR: Parcelable.Creator<Dpad> = object : Parcelable.Creator<Dpad> {
            override fun createFromParcel(parcel: Parcel): Dpad {
                return Dpad(parcel)
            }

            override fun newArray(size: Int): Array<Dpad?> {
                return arrayOfNulls(size)
            }
        }
    }
}
