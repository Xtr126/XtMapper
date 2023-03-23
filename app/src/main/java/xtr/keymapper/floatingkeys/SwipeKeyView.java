package xtr.keymapper.floatingkeys;

import android.content.Context;
import android.view.ViewGroup;

public class SwipeKeyView {

    public SwipeKeyView(ViewGroup rootView){
        Context context = rootView.getContext();
        MovableFloatingActionKey button1 = new MovableFloatingActionKey(context);
        MovableFloatingActionKey button2 = new MovableFloatingActionKey(context);

        SwipeKeyOverlay overlay = new SwipeKeyOverlay(context);
        rootView.addView(overlay);

        rootView.addView(button1);
        rootView.addView(button2);

        button1.setX(rootView.getPivotX() - 100);
        button1.setY(rootView.getPivotY() - 100);

        button2.setX(rootView.getPivotX() + 100);
        button2.setY(rootView.getPivotY() + 100);

        MovableFloatingActionKey.OnXyChangeListener xyChangeListener = (x, y) -> overlay.setLineXyFrom(button1, button2);

        button1.setXyChangeListener(xyChangeListener);
        button2.setXyChangeListener(xyChangeListener);
    }
}
