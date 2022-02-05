package com.xtr.keymapper;

import static android.content.Context.WINDOW_SERVICE;

import android.animation.Keyframe;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.text.InputFilter;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.nambimobile.widgets.efab.ExpandableFabLayout;
import com.nambimobile.widgets.efab.FabOption;
import com.nambimobile.widgets.efab.Orientation;
import com.xtr.keymapper.Layout.MovableFloatingActionButton;
import com.xtr.keymapper.Layout.MovableFrameLayout;
import com.xtr.keymapper.Layout.UppercaseEditText;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;


public class EditorUI {
    Context context;
    View keymapView;

    WindowManager.LayoutParams mParams;
    WindowManager mWindowManager;
    LayoutInflater layoutInflater;
    ExpandableFabLayout mainView;

    FabOption saveButton;
    FabOption addKey;
    FabOption dPad;
    FabOption crossHair;

    List<MovableFloatingActionButton> KeyX;

    private int i;

    public EditorUI(Context context) {
        this.context = context;
        i=0;
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);

        layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        keymapView = layoutInflater.inflate(R.layout.keymap, new ExpandableFabLayout(context), false);
        mainView = keymapView.findViewById(R.id.MainView);

        initFab();
        mParams.gravity = Gravity.CENTER;
        mWindowManager = (WindowManager) context.getSystemService(WINDOW_SERVICE);
        KeyX = new ArrayList<>();

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
            String s = "start db" + "\n";
            for (int i = 0; i < KeyX.size(); i++) {
                s += i + " " + KeyX.get(i).getX() + " " + KeyX.get(i).getY() + "\n";
            }
            FileWriter fileWriter = new FileWriter(context.getFilesDir().getPath() + "/keymap_config");
            PrintWriter printWriter = new PrintWriter(fileWriter);
            printWriter.print(s);
            printWriter.close();
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
        KeyX.add(i,MakeKey(String.valueOf(i)));
        mainView.addView(KeyX.get(i));
        MakeKeyLayout();
        i++;
    }
    private MovableFloatingActionButton MakeKey(String key) {
        MovableFloatingActionButton KeyA = new MovableFloatingActionButton(context);
        KeyA.setText(key);
        return KeyA;
    }
    private void MakeKeyLayout() {
        MovableFrameLayout KeyFrame = new MovableFrameLayout(context);
        ImageView imageView = new ImageView(context);
        imageView.setMaxHeight(50);
        imageView.setMaxWidth(50);
        imageView.setImageResource(R.drawable.key);

        UppercaseEditText KeyText = new UppercaseEditText(context);
        KeyText.setWidth(28);
        KeyText.setHeight(55);
        KeyText.setTextColor(Color.WHITE);
        KeyText.setCursorVisible(false);
        KeyText.setTextSize(30);
        KeyText.setFilters(new InputFilter[] { new InputFilter.LengthFilter(1) });

        LayoutParams l1 = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        l1.height = 50; l1.width = 50;
        KeyFrame.addView(imageView, l1);

        LayoutParams l2 = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        l2.height = 55; l2.width = 28;
        KeyFrame.addView(KeyText, l2);
        mainView.addView(KeyFrame);


    }

}
