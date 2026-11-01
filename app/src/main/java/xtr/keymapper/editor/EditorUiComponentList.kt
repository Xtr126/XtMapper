package xtr.keymapper.editor

import android.content.Context
import androidx.annotation.MenuRes
import xtr.keymapper.R
import xtr.keymapper.editor.component.*
import java.util.function.Consumer

internal class EditorUiComponentList : ArrayList<EditorUiComponent>() {

    fun newFactory(callback: EditorUiComponentCallback, context: Context): Factory {
        return Factory(callback, context)
    }

    fun addMatchingComponentForId(
        @MenuRes id: Int,
        callback: EditorUiComponentCallback,
        context: Context,
        x: Float,
        y: Float,
        addComponent: (EditorUiComponent) -> Boolean
    ) = when (id) {
        R.id.add -> Key(callback, context, x, y)
        R.id.crosshair -> Crosshair(callback, context, x, y)
        R.id.swipe_key -> SwipeKey(callback, context, x, y)
        R.id.shift_key -> ShiftKey(callback, context, x, y)
        R.id.mouse_right -> RightClick(callback, context, x, y)
        R.id.camera -> Camera(callback, context, x, y)
        R.id.mouse_walk -> MouseWalk(callback, context, x, y)
        R.id.dpad -> {
            // Dpad needs a type picker before it can be added, so this branch returns null.
            Dpad(callback, context, x, y).pickType { addComponent(it) }
            null
        }
        R.id.mouse_left -> {
            forEachComponentOfClass(Crosshair::class.java) { crosshair ->
                crosshair?.animateLeftClick(x, y)
            }
            null
        }
        else -> null
    }?.let(addComponent)

    fun <T> forEachComponentOfClass(clazz: Class<T>, action: Consumer<in T?>) {
        for (component in this) {
            if (clazz.isInstance(component)) {
                action.accept(clazz.cast(component))
            }
        }
    }

    internal inner class Factory(
        private val mCallback: EditorUiComponentCallback,
        private val context: Context
    ) {
        fun addKeys(keys: ArrayList<xtr.keymapper.keymap.element.Key>) {
            keys.forEach { key ->
                if (key != null) add(Key(mCallback, context, key))
            }
        }

        fun addSwipeKeys(swipeKeys: ArrayList<xtr.keymapper.keymap.element.SwipeKey>) {
            swipeKeys.forEach { swipeKey ->
                if (swipeKey != null) add(SwipeKey(mCallback, context, swipeKey))
            }
        }

        fun addShiftKeys(shiftKeys: ArrayList<xtr.keymapper.keymap.element.ShiftKey>) {
            shiftKeys.forEach { shiftKey ->
                if (shiftKey != null) add(ShiftKey(mCallback, context, shiftKey))
            }
        }

        fun addDpads(dpadArray: Array<xtr.keymapper.keymap.element.Dpad?>) {
            for (dpad in dpadArray) {
                if (dpad != null) add(Dpad(mCallback, context, dpad))
            }
        }
    }
}
