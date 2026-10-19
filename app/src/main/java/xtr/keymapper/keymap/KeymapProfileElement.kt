package xtr.keymapper.keymap

import android.os.Parcelable

abstract class KeymapProfileElement : Parcelable {
    abstract fun scale(scaleX: Float, scaleY: Float)
}
