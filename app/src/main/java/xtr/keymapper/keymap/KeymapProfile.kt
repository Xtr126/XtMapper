package xtr.keymapper.keymap

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import xtr.keymapper.BuildConfig
import xtr.keymapper.keymap.element.Camera
import xtr.keymapper.keymap.element.Dpad
import xtr.keymapper.keymap.element.Key
import xtr.keymapper.keymap.element.MouseAimConfig
import xtr.keymapper.keymap.element.MouseWalk
import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.keymap.element.SwipeKey
import xtr.keymapper.macro.Macro
import xtr.keymapper.macro.MacroSharedPreferences

class KeymapProfile : Parcelable {

    @JvmField
    var packageName: String = BuildConfig.APPLICATION_ID

    @JvmField
    val dpadArray: Array<Dpad?>

    @JvmField
    var mouseAimConfig: MouseAimConfig? = null

    @JvmField
    var mouseWalk: MouseWalk? = null

    @JvmField
    var keys = ArrayList<Key>()

    @JvmField
    var swipeKeys = ArrayList<SwipeKey>()

    @JvmField
    var shiftKeys = ArrayList<ShiftKey>()

    @JvmField
    var rightClick: Key? = null

    @JvmField
    var disabled = false

    @JvmField
    var xRes = 0

    @JvmField
    var yRes = 0

    @JvmField
    val macroIdMap = HashMap<String, Macro>()

    @JvmField
    var camera: Camera? = null

    constructor() {
        dpadArray = arrayOfNulls(MAX_DPADS)
    }

    fun scale(newWidth: Float, newHeight: Float) {
        val scaleX = if (xRes > 0) newWidth / xRes else 1f
        val scaleY = if (yRes > 0) newHeight / yRes else 1f

        if (xRes > 0 && yRes > 0) {
            // Guard: don't scale if dimensions are virtually identical
            if (Math.abs(scaleX - 1f) < 0.001f && Math.abs(scaleY - 1f) < 0.001f) {
                return
            }
            // Guard against inset-induced false scaling:
            // If xRes matches newWidth and the difference between yRes and newHeight is just system insets (< 15%),
            // this is an inset mismatch between fullscreen and windowed on the same device, not a different resolution.
            if (Math.abs(scaleX - 1f) < 0.01f && Math.abs(scaleY - 1f) < 0.15f) {
                xRes = newWidth.toInt()
                yRes = newHeight.toInt()
                return
            }

            val scaler = { element: KeymapProfileElement? ->
                if (element != null)
                    element.scale(scaleX, scaleY)
            }
            keys.forEach { scaler(it) }
            swipeKeys.forEach { scaler(it) }
            shiftKeys.forEach { scaler(it) }
            for (dpad in dpadArray) {
                scaler(dpad)
            }
            scaler(rightClick)
            scaler(mouseAimConfig)
            scaler(mouseWalk)

            xRes = newWidth.toInt()
            yRes = newHeight.toInt()
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    protected constructor(parcel: Parcel) {
        packageName = parcel.readString()!!
        dpadArray = parcel.createTypedArray(Dpad.CREATOR) as Array<Dpad?>
        mouseAimConfig = parcel.readParcelable(MouseAimConfig::class.java.classLoader)
        keys = parcel.createTypedArrayList(Key.CREATOR) as ArrayList<Key>
        swipeKeys = parcel.createTypedArrayList(SwipeKey.CREATOR) as ArrayList<SwipeKey>
        shiftKeys = parcel.createTypedArrayList(ShiftKey.CREATOR) as ArrayList<ShiftKey>
        rightClick = parcel.readParcelable(Key::class.java.classLoader)
        disabled = parcel.readByte().toInt() != 0
        xRes = parcel.readInt()
        yRes = parcel.readInt()
        val hashMap = parcel.readHashMap(javaClass.classLoader)
        if (hashMap != null) macroIdMap.putAll(hashMap as Map<String, Macro>)
        camera = parcel.readParcelable(Camera::class.java.classLoader)
        mouseWalk = parcel.readParcelable(MouseWalk::class.java.classLoader)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(packageName)
        dest.writeTypedArray(dpadArray, flags)
        dest.writeParcelable(mouseAimConfig, flags)
        dest.writeTypedList(keys)
        dest.writeTypedList(swipeKeys)
        dest.writeTypedList(shiftKeys)
        dest.writeParcelable(rightClick, flags)
        dest.writeByte((if (disabled) 1 else 0).toByte())
        dest.writeInt(xRes)
        dest.writeInt(yRes)
        dest.writeMap(macroIdMap)
        dest.writeParcelable(camera, flags)
        dest.writeParcelable(mouseWalk, flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    // Java protected = package + subclass; KeymapProfiles (same package) calls this
    internal fun loadMacros(context: Context) {
        val macroSharedPreferences = MacroSharedPreferences(context)
        macroIdMap.replaceAll { macroId, existingMacro ->
            val loadedMacro = macroSharedPreferences.getMacro(macroId)
            loadedMacro.triggerKey = existingMacro.triggerKey
            loadedMacro
        }
    }

    companion object {
        const val MAX_DPADS = 3

        @JvmField
        val CREATOR: Parcelable.Creator<KeymapProfile> = object : Parcelable.Creator<KeymapProfile> {
            override fun createFromParcel(parcel: Parcel): KeymapProfile {
                return KeymapProfile(parcel)
            }

            override fun newArray(size: Int): Array<KeymapProfile?> {
                return arrayOfNulls(size)
            }
        }
    }
}
