package xtr.keymapper.editor.component;

import static xtr.keymapper.keymap.KeymapProfiles.MOUSE_RIGHT;

import android.content.Context;

import xtr.keymapper.R;
import xtr.keymapper.editor.EditorUiComponent;
import xtr.keymapper.editor.EditorUiComponentCallback;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.keymap.element.Key;

public class RightClick extends EditorUiComponent {

    private final MovableFloatingActionKey rightClick;

    public RightClick(EditorUiComponentCallback mCallback, Context context, float x, float y) {
        super(mCallback, context, x, y);
        rightClick = new MovableFloatingActionKey(context, floatingActionKey -> {
            getCallback().removeComponent(this, floatingActionKey.frameView);
        }, mCallback.getKeysContainerView());
        rightClick.frameView.setBackgroundResource(R.drawable.ic_baseline_mouse_36);
        rightClick.setText(R.string.right_click);
        rightClick.frameView.animate().x(x).y(y)
                .setDuration(500)
                .start();

    }

    @Override
    public String getDataLine() {
        return MOUSE_RIGHT + " " + rightClick.getX() + " " + rightClick.getY();
    }

    public RightClick(EditorUiComponentCallback mCallback, Context context, Key rightClick) {
        this(mCallback, context, rightClick.x, rightClick.y);
    }
}
