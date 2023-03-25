package xtr.keymapper.swipekey;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.button.MaterialButton;

import xtr.keymapper.databinding.SwipeKeyBinding;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;

public class SwipeKeyView {

    public final MovableFloatingActionKey button1, button2;
    public final MaterialButton closeButton;
    public final SwipeKeyOverlay overlay;

    public interface OnViewRemoved {
        void onViewRemoved(SwipeKeyView swipeKeyView);
    }

    public SwipeKeyView(ViewGroup mainView, SwipeKey swipeKey, OnViewRemoved callback, View.OnClickListener onClickListener) {
        this(mainView, callback, onClickListener);
        button1.setText(swipeKey.key1.code);
        button1.animate()
                .x(swipeKey.key1.x)
                .y(swipeKey.key1.y)
                .setDuration(500)
                .start();

        button2.setText(swipeKey.key2.code);
        button2.animate()
                .x(swipeKey.key2.x)
                .y(swipeKey.key2.y)
                .setDuration(500)
                .start();
        Handler handler = new Handler(Looper.myLooper());
        handler.postDelayed(() -> onXyChange(0, 0), 500);
    }

    public SwipeKeyView(ViewGroup rootView, OnViewRemoved callback, View.OnClickListener onClickListener){
        Context context = rootView.getContext();
        button1 = new MovableFloatingActionKey(context);
        button2 = new MovableFloatingActionKey(context);

        closeButton = SwipeKeyBinding.inflate(LayoutInflater.from(context)).getRoot();

        overlay = new SwipeKeyOverlay(context);
        rootView.addView(overlay);

        rootView.addView(button1);
        rootView.addView(button2);
        rootView.addView(closeButton);

        closeButton.setOnClickListener(v -> {
            rootView.removeView(button1);
            rootView.removeView(button2);
            rootView.removeView(closeButton);
            rootView.removeView(overlay);
            callback.onViewRemoved(this);
        });

        button1.setX(rootView.getPivotX() - 100);
        button1.setY(rootView.getPivotY() - 100);

        button2.setX(rootView.getPivotX() + 100);
        button2.setY(rootView.getPivotY() + 100);

        button1.setXyChangeListener(this::onXyChange);
        button2.setXyChangeListener(this::onXyChange);

        button1.setOnClickListener(onClickListener);
        button2.setOnClickListener(onClickListener);
    }

    private void onXyChange(float x, float y) {
        overlay.setLineXyFrom(button1, button2);
        overlay.centerViewOnLine(closeButton);
    }
}
