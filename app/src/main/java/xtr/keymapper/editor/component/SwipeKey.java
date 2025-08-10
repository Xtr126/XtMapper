package xtr.keymapper.editor.component;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.google.android.material.button.MaterialButton;

import xtr.keymapper.databinding.SwipeKeyBinding;
import xtr.keymapper.editor.EditorUiComponent;
import xtr.keymapper.editor.EditorUiComponentCallback;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.swipekey.SwipeKeyOverlay;

public class SwipeKey extends EditorUiComponent {
    private final SwipeKeyView swipeKeyView;

    public SwipeKey(EditorUiComponentCallback mCallback, Context context, float x, float y) {
        super(mCallback, context, x, y);
        swipeKeyView = new SwipeKeyView(mCallback.getKeysContainerView(), new SwipeKeyView.SwipeKeyCallback() {
            @Override
            public void onSwipeKeyRemoved() {
                mCallback.removeComponent(SwipeKey.this);
            }

            @Override
            public void onViewClicked(MovableFloatingActionKey view) {
                getCallback().setOnKeyListener(view::setText);
            }
        });
    }

    public SwipeKey(EditorUiComponentCallback mCallback, Context context, xtr.keymapper.keymap.element.SwipeKey swipeKey) {
        super(mCallback, context, 0, 0);
        swipeKeyView = new SwipeKeyView(mCallback.getKeysContainerView(), swipeKey, new SwipeKeyView.SwipeKeyCallback() {
            @Override
            public void onSwipeKeyRemoved() {
                mCallback.removeComponent(SwipeKey.this);
            }

            @Override
            public void onViewClicked(MovableFloatingActionKey view) {
                getCallback().setOnKeyListener(view::setText);
            }
        });

    }

    @Override
    public String getDataLine() {
        xtr.keymapper.keymap.element.SwipeKey swipeKey = new xtr.keymapper.keymap.element.SwipeKey();
        swipeKey.key1.code = swipeKeyView.button1.getText();
        swipeKey.key1.x = swipeKeyView.button1.getX();
        swipeKey.key1.y = swipeKeyView.button1.getY();

        swipeKey.key2.code = swipeKeyView.button2.getText();
        swipeKey.key2.x = swipeKeyView.button2.getX();
        swipeKey.key2.y = swipeKeyView.button2.getY();

        return swipeKey.getData();
    }

    static class SwipeKeyView {

        public final MovableFloatingActionKey button1, button2;
        public final MaterialButton closeButton;
        public final SwipeKeyOverlay overlay;

        public interface SwipeKeyCallback {
            void onSwipeKeyRemoved();
            void onViewClicked(MovableFloatingActionKey view);
        }

        /* From saved profile */
        public SwipeKeyView(ViewGroup rootView, xtr.keymapper.keymap.element.SwipeKey swipeKey, SwipeKeyCallback callback) {
            this(rootView, callback);
            button1.setText(swipeKey.key1.code);
            button1.frameView.animate()
                    .x(swipeKey.key1.x)
                    .y(swipeKey.key1.y)
                    .setDuration(500)
                    .withEndAction(() -> onXyChange(0, 0))
                    .start();

            button2.setText(swipeKey.key2.code);
            button2.frameView.animate()
                    .x(swipeKey.key2.x)
                    .y(swipeKey.key2.y)
                    .setDuration(500)
                    .withEndAction(() -> onXyChange(0, 0))
                    .start();

            KeymapConfig keymapConfig = new KeymapConfig(rootView.getContext());

            button1.frameView.setScaleX(keymapConfig.floatingKeysSize);
            button1.frameView.setScaleY(keymapConfig.floatingKeysSize);

            button2.frameView.setScaleX(keymapConfig.floatingKeysSize);
            button2.frameView.setScaleY(keymapConfig.floatingKeysSize);
        }

        /* New swipe key */
        public SwipeKeyView(ViewGroup rootView, SwipeKeyCallback callback){
            Context context = rootView.getContext();
            button1 = new MovableFloatingActionKey(context, true, rootView);
            button2 = new MovableFloatingActionKey(context, true, rootView);

            closeButton = SwipeKeyBinding.inflate(LayoutInflater.from(context), rootView, true).getRoot();

            overlay = new SwipeKeyOverlay(context);
            rootView.addView(overlay);

            closeButton.setOnClickListener(v -> {
                rootView.removeView(button1.frameView);
                rootView.removeView(button2.frameView);
                rootView.removeView(closeButton);
                rootView.removeView(overlay);
                callback.onSwipeKeyRemoved();
            });

            button1.setX(rootView.getPivotX() - 100);
            button1.setY(rootView.getPivotY() - 100);

            button2.setX(rootView.getPivotX() + 100);
            button2.setY(rootView.getPivotY() + 100);

            button1.setXyChangeListener(this::onXyChange);
            button2.setXyChangeListener(this::onXyChange);

            button1.setOnClickListener(v -> callback.onViewClicked(button1));
            button2.setOnClickListener(v -> callback.onViewClicked(button2));
        }

        private void onXyChange(float x, float y) {
            overlay.setLineXyFrom(button1.frameView, button2.frameView);
            overlay.centerViewOnLine(closeButton);
        }
    }
}


