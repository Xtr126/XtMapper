package xtr.keymapper.editor.component;

import android.content.Context;
import android.view.View;

import xtr.keymapper.editor.EditorUiComponent;
import xtr.keymapper.editor.EditorUiComponentCallback;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfileKey;

public class Key extends EditorUiComponent {
    private final MovableFloatingActionKey floatingKey;

    public Key(EditorUiComponentCallback callback, Context context, float x, float y) {
        super(callback, context, x, y);
        KeymapProfileKey key = new KeymapProfileKey();
        key.code = "KEY_X";
        key.x = x;
        key.y = y;
        floatingKey = addKey(key);
    }

    @Override
    public String getDataLine() {
        return floatingKey.getData();
    }


    public Key(EditorUiComponentCallback callback, Context context, KeymapProfileKey key) {
        super(callback, context, key.x, key.y);
        floatingKey = addKey(key);
    }

    private MovableFloatingActionKey addKey(KeymapProfileKey key) {
        KeymapConfig keymapConfig = new KeymapConfig(getContext());

        MovableFloatingActionKey floatingKey = new MovableFloatingActionKey(getContext(), floatingActionKey -> {
            getCallback().removeComponent(this, floatingActionKey.frameView);
        }, getCallback().getKeysContainerView());

        floatingKey.setText(key.code.substring(4));
        floatingKey.frameView.animate()
                .x(key.x)
                .y(key.y)
                .setDuration(1000)
                .start();
        floatingKey.setOnClickListener(this::onFloatingKeyClick);

        floatingKey.frameView.setScaleX(keymapConfig.floatingKeysSize);
        floatingKey.frameView.setScaleY(keymapConfig.floatingKeysSize);

        return floatingKey;
    }

    private void onFloatingKeyClick(View view) {
        getCallback().setOnKeyListener(floatingKey::setText);
    }

}
