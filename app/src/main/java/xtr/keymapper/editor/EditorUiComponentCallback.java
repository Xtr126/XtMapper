package xtr.keymapper.editor;

import android.view.View;
import android.view.ViewGroup;

import xtr.keymapper.keymap.KeymapProfile;

public interface EditorUiComponentCallback {

    boolean isOverlayOpen();

    KeymapProfile getProfile();

    ViewGroup getKeysContainerView();

    void removeComponent(EditorUiComponent component, View... viewsToRemove);

    void setOnKeyListener(EditorUI.KeyInFocusListener keyInFocusListener);
}
