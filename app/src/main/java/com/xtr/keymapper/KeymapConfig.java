package com.xtr.keymapper;
import android.content.Context;
import android.view.View;
import android.widget.EditText;

import com.xtr.keymapper.Layout.MovableFrameLayout;

import java.util.List;

public class KeymapConfig {
    Context context;
    String s;
    float x; float y;
    //EditText[] KeyText;
    //MovableFrameLayout[] KeyFrame;
    public KeymapConfig(Context context, List<EditText> KeyText,
                        List<View> KeyLayout,
                        List<MovableFrameLayout> KeyFrame) {
        this.context = context;
        for (int i = 0; i < KeyText.size(); i++) {
            x = KeyFrame.get(i).getX();
            y = KeyFrame.get(i).getY();
            s += KeyText.get(i).getText().toString() + " " + x + " " + y + "\n";
        }
    }
    public void save() {
    }
}