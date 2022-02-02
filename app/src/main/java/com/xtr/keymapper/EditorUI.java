package com.xtr.keymapper;

import static android.content.Context.WINDOW_SERVICE;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.nambimobile.widgets.efab.ExpandableFab;
import com.nambimobile.widgets.efab.ExpandableFabLayout;
import com.nambimobile.widgets.efab.FabOption;
import com.nambimobile.widgets.efab.FabSize;

public class EditorUI {

    private final Context context;
    private final View keymapView;
    private final WindowManager.LayoutParams mParams;
    private final WindowManager mWindowManager;
    FabOption saveButton;
    FabOption addKey;
    FabOption dPad;
    FabOption crossHair;
    ExpandableFabLayout mainView;
    public EditorUI(Context context) {
        this.context = context;
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        keymapView = layoutInflater.inflate(R.layout.keymap, null);
        mainView = keymapView.findViewById(R.id.MainView);
        initFab();
        mParams.gravity = Gravity.CENTER;
        mWindowManager = (WindowManager) context.getSystemService(WINDOW_SERVICE);
    }
    public void open() {
        try {
            if (keymapView.getWindowToken() == null) {
                if (keymapView.getParent() == null) {
                    mWindowManager.addView(mainView, mParams);
                }
            }
        } catch (Exception e) {
            Log.d("Error1", e.toString());
        }
    }
    public void hideView() {
        try {
            ((WindowManager) context.getSystemService(WINDOW_SERVICE)).removeView(keymapView);
            keymapView.invalidate();
            // remove all views
            ((ViewGroup) keymapView.getParent()).removeAllViews();
            // the above steps are necessary when you are adding and removing
            // the view simultaneously, it might give some exceptions
        } catch (Exception e) {
            Log.d("Error2", e.toString());
        }
    }
    public void initFab() {
        saveButton = mainView.findViewById(R.id.save_button);
        addKey = mainView.findViewById(R.id.add_button);
        dPad = mainView.findViewById(R.id.d_pad);
        crossHair = mainView.findViewById(R.id.cross_hair);

        saveButton.setOnClickListener(v -> hideView());
        addKey.setOnClickListener(v -> addKey());

        dPad.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        crossHair.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        saveButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        addKey.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

    }

    private void addKey() {
        MovableFloatingActionButton KeyA = new MovableFloatingActionButton(context);
        KeyA.setImageBitmap(KeyA.setText("A"));
        KeyA.setScaleType(ImageView.ScaleType.CENTER);
        mainView.addView(KeyA);
    }

}
