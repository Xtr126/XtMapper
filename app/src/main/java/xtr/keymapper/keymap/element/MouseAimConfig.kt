package xtr.keymapper.keymap.element

import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.floatingkeys.MovableFloatingActionKey
import xtr.keymapper.floatingkeys.MovableFrameLayout
import xtr.keymapper.keymap.KeymapProfileElement

class MouseAimConfig : KeymapProfileElement {

    @JvmField
    var xCenter = initXY.toFloat()

    @JvmField
    var yCenter = initXY.toFloat()

    @JvmField
    var xleftClick = initXY.toFloat()

    @JvmField
    var yleftClick = initXY.toFloat()

    @JvmField
    var width = 0f

    @JvmField
    var height = 0f

    @JvmField
    var limitedBounds = true

    @JvmField
    var xSensitivity = 1f

    @JvmField
    var ySensitivity = 1f

    @JvmField
    var applyNonLinearScaling = false

    override fun scale(scaleX: Float, scaleY: Float) {
        xCenter *= scaleX
        yCenter *= scaleY

        xleftClick *= scaleX
        yleftClick *= scaleY

        width *= scaleX
        height *= scaleY
    }

    constructor()

    protected constructor(parcel: Parcel) {
        xCenter = parcel.readFloat()
        yCenter = parcel.readFloat()
        xleftClick = parcel.readFloat()
        yleftClick = parcel.readFloat()
        width = parcel.readFloat()
        height = parcel.readFloat()
        limitedBounds = parcel.readByte().toInt() != 0
        xSensitivity = parcel.readFloat()
        ySensitivity = parcel.readFloat()
        applyNonLinearScaling = parcel.readByte().toInt() != 0
    }

    constructor(
        xCenter: Float, yCenter: Float,
        xleftClick: Float, yleftClick: Float,
        width: Float, height: Float,
        limitedBounds: Boolean,
        xSensitivity: Float, ySensitivity: Float,
        applyNonLinearScaling: Boolean
    ) {
        this.xCenter = xCenter
        this.yCenter = yCenter
        this.xleftClick = xleftClick
        this.yleftClick = yleftClick
        this.width = width
        this.height = height
        this.limitedBounds = limitedBounds
        this.xSensitivity = xSensitivity
        this.ySensitivity = ySensitivity
        this.applyNonLinearScaling = applyNonLinearScaling
    }

    constructor(data: Array<String>) {
        xCenter = data[1].toFloat()
        yCenter = data[2].toFloat()
        limitedBounds = data[3].toInt() != 0
        width = data[4].toFloat()
        height = data[5].toFloat()
        xleftClick = data[6].toFloat()
        yleftClick = data[7].toFloat()
        if (data.size == 11) {
            xSensitivity = data[8].toFloat()
            ySensitivity = data[9].toFloat()
            applyNonLinearScaling = data[10].toInt() != 0
        }
    }

    val data: String
        get() {
        return TAG + " " + xCenter + " " + yCenter + " " +
                (if (limitedBounds) 1 else 0) + " " +
                width + " " + height + " " +
                xleftClick + " " + yleftClick + " " +
                xSensitivity + " " + ySensitivity + " " +
                (if (applyNonLinearScaling) 1 else 0)
    }

    fun setCenterXY(crosshair: MovableFrameLayout) {
        this.xCenter = crosshair.x
        this.yCenter = crosshair.y
    }

    fun setLeftClickXY(leftClick: MovableFloatingActionKey) {
        this.xleftClick = leftClick.getX()
        this.yleftClick = leftClick.getY()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeFloat(xCenter)
        dest.writeFloat(yCenter)
        dest.writeFloat(xleftClick)
        dest.writeFloat(yleftClick)
        dest.writeFloat(width)
        dest.writeFloat(height)
        dest.writeByte((if (limitedBounds) 1 else 0).toByte())
        dest.writeFloat(xSensitivity)
        dest.writeFloat(ySensitivity)
        dest.writeByte((if (applyNonLinearScaling) 1 else 0).toByte())
    }

    companion object {
        private const val initXY = 300
        const val TAG = "MOUSE_AIM"

        @JvmField
        val CREATOR: Parcelable.Creator<MouseAimConfig> =
            object : Parcelable.Creator<MouseAimConfig> {
                override fun createFromParcel(parcel: Parcel): MouseAimConfig {
                    return MouseAimConfig(parcel)
                }

                override fun newArray(size: Int): Array<MouseAimConfig?> {
                    return arrayOfNulls(size)
                }
            }
    }
}
