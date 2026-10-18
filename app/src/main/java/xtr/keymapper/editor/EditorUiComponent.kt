package xtr.keymapper.editor

import android.content.Context
import android.view.LayoutInflater

// x, y are unused, kept for subclass ctor compatibility
@Suppress("UNUSED_PARAMETER")
abstract class EditorUiComponent(
    protected val callback: EditorUiComponentCallback,
    protected val context: Context,
    x: Float,
    y: Float
) {
    protected val layoutInflater: LayoutInflater
        get() = context.getSystemService(LayoutInflater::class.java)

    abstract fun toConfigLine(): String
}
