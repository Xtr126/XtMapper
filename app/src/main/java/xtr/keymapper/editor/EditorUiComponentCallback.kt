package xtr.keymapper.editor

import android.view.View
import android.view.ViewGroup
import xtr.keymapper.keymap.KeymapProfile

interface EditorUiComponentCallback {

    fun isOverlayOpen(): Boolean

    // Properties, not getters: existing Kotlin callers use .profile/.keysContainerView
    val profile: KeymapProfile

    val keysContainerView: ViewGroup

    fun removeComponent(component: EditorUiComponent?, vararg viewsToRemove: View?)

    fun setOnKeyListener(keyInFocusListener: EditorUI.KeyInFocusListener?)
}
