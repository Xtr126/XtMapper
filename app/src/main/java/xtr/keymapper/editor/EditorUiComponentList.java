package xtr.keymapper.editor;

import android.content.Context;

import androidx.annotation.MenuRes;

import java.util.ArrayList;
import java.util.function.Consumer;

import xtr.keymapper.R;
import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.editor.component.Crosshair;
import xtr.keymapper.editor.component.Key;
import xtr.keymapper.editor.component.RightClick;
import xtr.keymapper.keymap.KeymapProfileKey;
import xtr.keymapper.swipekey.SwipeKey;

class EditorUiComponentList extends ArrayList<EditorUiComponent> {

    public Factory newFactory(EditorUiComponentCallback callback, Context context) {
        return new Factory(callback, context);
    }

    public void addMatchingComponentForId(@MenuRes int id, EditorUiComponentCallback callback, Context context, float x, float y) {
        final EditorUiComponent component;
        if (id == R.id.add) {
            component = new Key(callback, context, x, y);
        }
        else if (id == R.id.dpad) {
            component = new xtr.keymapper.editor.component.Dpad(callback, context, x, y).pickType();
        }
        else if (id == R.id.crosshair) {
            component = new Crosshair(callback, context, x, y);
        }
        else if (id == R.id.swipe_key) {
            component = new xtr.keymapper.editor.component.SwipeKey(callback, context, x, y);
        }
        else if (id == R.id.mouse_right) {
            component = new RightClick(callback, context, x, y);
        }
        else if (id == R.id.mouse_left) {
            component = null;
            forEachComponentOfClass(Crosshair.class,
                    crosshair -> crosshair.animateLeftClick(x, y));
        }
        else if (id == R.id.camera) {
            component = null;
        }
        else {
            component = null;
        }
        if (component != null) add(component);
    }

    public <T> void forEachComponentOfClass(Class<T> clazz, Consumer<? super T> action) {
        for (EditorUiComponent component : this) {
            if (clazz.isInstance(component)) {
                action.accept(clazz.cast(component));
            }
        }
    }



    class Factory {
        private final EditorUiComponentCallback mCallback;
        private final Context context;

        public Factory(EditorUiComponentCallback callback, Context context) {
            this.mCallback = callback;
            this.context = context;
        }

        public void addKeys(ArrayList<KeymapProfileKey> keys) {
            keys.forEach( key -> add(new Key(mCallback, context, key)));
        }

        public void addSwipeKeys(ArrayList<SwipeKey> swipeKeys) {
            swipeKeys.forEach(swipeKey -> add(new xtr.keymapper.editor.component.SwipeKey(mCallback, context, swipeKey)));
        }

        public void addDpads(Dpad[] dpadArray) {
            for (Dpad dpad: dpadArray) {
                if (dpad != null)
                    add(new xtr.keymapper.editor.component.Dpad(mCallback, context, dpad));
            }
        }

    }

}
