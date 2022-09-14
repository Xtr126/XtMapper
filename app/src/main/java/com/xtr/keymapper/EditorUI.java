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

import com.nambimobile.widgets.efab.ExpandableFabLayout;
import com.nambimobile.widgets.efab.FabOption;
import com.xtr.keymapper.Layout.MovableFloatingActionButton;
import com.xtr.keymapper.Layout.MovableFloatingActionButton;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
                    loadKeymap();
                }
            }
        } catch (Exception e) {
            Log.d("Error1", e.toString());
        }
    }

    private void loadKeymap() throws IOException {
        KeymapConfig keymapConfig =  new KeymapConfig(context);
        List<String> stream = Files.readAllLines(Paths.get(KeymapConfig.configPath));
        stream.forEach(keymapConfig::loadConfig);
        String[] key = keymapConfig.getKey();
        Float[] x = keymapConfig.getX();
        Float[] y = keymapConfig.getY();

        for (int n = 0; n < key.length; n++) {
            if (key[n] != null) {
                KeyX.add(i, new MovableFloatingActionButton(context));
                mainView.addView(KeyX.get(i));
                KeyX.get(i).setText(key[n]);
                KeyX.get(i).setX(x[n]);
                KeyX.get(i).setY(y[n]);
                i++;
            }
        }
    }


    public void hideView() {
        try {
            saveKeymap();
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

    private void saveKeymap() throws IOException {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < KeyX.size(); i++) {
            s.append(KeyX.get(i).getData());
        }
        FileWriter fileWriter = new FileWriter(KeymapConfig.configPath);
        PrintWriter printWriter = new PrintWriter(fileWriter);
        printWriter.print(s);
        printWriter.close();
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
        KeyX.add(i,new MovableFloatingActionButton(context));
        mainView.addView(KeyX.get(i));
        KeyX.get(i).setText(String.valueOf(i));
        i++;
    }

}
