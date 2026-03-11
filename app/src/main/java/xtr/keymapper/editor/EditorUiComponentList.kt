package xtr.keymapper.editor

// SoufianoDev: Standard Editor Imports And Component Dependencies.
import android.content.Context
import androidx.annotation.MenuRes
import xtr.keymapper.R
import xtr.keymapper.editor.component.*
import java.util.function.Consumer

internal class EditorUiComponentList : ArrayList<EditorUiComponent>() {

    // SoufianoDev: Initialize A New Component Factory For Shared Callback Context.
    fun newFactory(callback: EditorUiComponentCallback, context: Context): Factory {
        return Factory(callback, context)
    }

    /* Note : This Method Maps Menu IDs To Functional UI Components.
       The New Implementation Replaces Unsafe Double Bangs (!!) With Safe Calls
       And Enforces Non-Nullable Contexts To Enhance Editor Stability.
    */
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
        R.id.mouse_right -> RightClick(callback, context, x, y)
        R.id.camera -> Camera(callback, context, x, y)
        R.id.mouse_walk -> MouseWalk(callback, context, x, y)
        R.id.dpad -> {
            // SoufianoDev: Dpad Selection Requires A Context Picker Before Adding To Screen.
            Dpad(callback, context, x, y).pickType { addComponent(it) }
            null
        }
        R.id.mouse_left -> {
            // SoufianoDev: Animate Click Visuals Across All Active Crosshair Instances.
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
        // SoufianoDev: Batch Load Physical Keys Into The UI Editor Space.
        fun addKeys(keys: ArrayList<xtr.keymapper.keymap.element.Key?>) {
            keys.forEach { key ->
                if (key != null) add(Key(mCallback, context, key))
            }
        }

        // SoufianoDev: Batch Load Swipe Gestures Into The UI Editor Space.
        fun addSwipeKeys(swipeKeys: ArrayList<xtr.keymapper.keymap.element.SwipeKey?>) {
            swipeKeys.forEach { swipeKey ->
                if (swipeKey != null) add(SwipeKey(mCallback, context, swipeKey))
            }
        }

        fun addDpads(dpadArray: Array<xtr.keymapper.keymap.element.Dpad?>) {
            for (dpad in dpadArray) {
                if (dpad != null) add(Dpad(mCallback, context, dpad))
            }
        }
    }
}
