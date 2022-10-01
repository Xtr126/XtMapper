package com.xtr.keymapper;


import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.nambimobile.widgets.efab.ExpandableFabLayout;
import com.nambimobile.widgets.efab.FabOption;
import com.xtr.keymapper.Layout.FloatingActionKey;
import com.xtr.keymapper.Layout.MovableFrameLayout;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;


public class EditorUI extends AppCompatActivity {
    private View keymapView;

    private WindowManager.LayoutParams mParams;
    private WindowManager mWindowManager;
    private LayoutInflater layoutInflater;
    private ExpandableFabLayout mainView;

    private FloatingActionKey KeyInFocus;
    private List<FloatingActionKey> KeyX;
    private MovableFrameLayout dpad1;
    private MovableFrameLayout dpad2;
    private final Float DEFAULT_X = 200f;
    private final Float DEFAULT_Y = 200f;
    int i = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        layoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        keymapView = layoutInflater.inflate(R.layout.keymap, new ExpandableFabLayout(this), false);
        mainView = keymapView.findViewById(R.id.MainView);
        initFab();
        mParams.gravity = Gravity.CENTER;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        KeyX = new ArrayList<>();
        open();
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

    public void hideView() {
        try {
            saveKeymap();
            this.finish();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(keymapView);
            keymapView.invalidate();
            // remove all views
            ((ViewGroup) keymapView.getParent()).removeAllViews();
            this.finish();
            // the above steps are necessary when you are adding and removing
            // the view simultaneously, it might give some exceptions
        } catch (Exception e) {
            Log.d("Error2", e.toString());
        }
    }

    private void loadKeymap() throws IOException {
        KeymapConfig keymapConfig = new KeymapConfig(this);
        keymapConfig.loadConfig();
        String[] keys = keymapConfig.getKeys();
        Float[] key_x = keymapConfig.getX();
        Float[] key_y = keymapConfig.getY();

        for (int n = 0; n < keys.length; n++) {
            if (keys[n] != null) {
                addKey(keys[n], key_x[n], key_y[n]);
            }
        }

        String[] dpad1 = keymapConfig.dpad1Config;
        String[] dpad2 = keymapConfig.dpad2Config;

        if (dpad1 != null) {
            Float x1 = Float.parseFloat(dpad1[1]);
            Float y1 = Float.parseFloat(dpad1[2]);
            addDpad1(x1, y1);
        }

        if (dpad2 != null) {
            Float x2 = Float.parseFloat(dpad2[1]);
            Float y2 = Float.parseFloat(dpad2[2]);
            addDpad1(x2, y2);
        }
    }

    private void saveKeymap() throws IOException {
        StringBuilder linesToWrite = new StringBuilder();
        for (int i = 0; i < KeyX.size(); i++) {
            if(KeyX.get(i).key != null) {
                linesToWrite.append(KeyX.get(i).getData());
            }
        }
        if (dpad1 != null) {
            linesToWrite.append("UDLR_DPAD ")
                        .append(dpad1.getX()).append(" ")
                        .append(dpad1.getY()).append("\n");
        }
        if (dpad2 != null) {
            linesToWrite.append("WASD_DPAD ")
                        .append(dpad2.getX()).append(" ")
                        .append(dpad2.getY()).append("\n");
        }
        FileWriter fileWriter = new FileWriter(KeymapConfig.getConfigPath(this));
        PrintWriter printWriter = new PrintWriter(fileWriter);
        printWriter.print(linesToWrite);
        printWriter.close();
    }

    public void initFab() {
        FabOption saveButton = mainView.findViewById(R.id.save_button);
        FabOption addKey = mainView.findViewById(R.id.add_button);
        FabOption dPad = mainView.findViewById(R.id.d_pad);
        FabOption crossHair = mainView.findViewById(R.id.cross_hair);

        saveButton.setOnClickListener(v -> hideView());
        addKey.setOnClickListener(v -> addKey("A", DEFAULT_X, DEFAULT_Y));

        dPad.setOnClickListener(new View.OnClickListener() {
            int x = 0;
            @Override
            public void onClick(View v) {
                if (x == 0) {
                    addDpad1(DEFAULT_X, DEFAULT_Y);
                    x = 1;
                } else {
                    addDpad2(DEFAULT_X, DEFAULT_Y);
                    x = 0;
                }
            }
        });

        dPad.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        crossHair.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        saveButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        addKey.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }

    private void addDpad1(Float x, Float y) {
        if (dpad1 != null) {
            dpad1.removeAllViews();
        }
        dpad1 = layoutInflater.inflate(R.layout.d_pad_1, mainView, true)
                .findViewById(R.id.dpad1);

        dpad1.findViewById(R.id.closeButton)
                .setOnClickListener(v -> {
                    mainView.removeView(dpad1);
                    dpad1 = null;
                });

        dpad1.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void addDpad2(Float x, Float y) {
        if (dpad2 != null) {
            dpad2.removeAllViews();
        }
        dpad2 = layoutInflater.inflate(R.layout.d_pad_2, mainView, true)
                .findViewById(R.id.dpad2);

        dpad2.findViewById(R.id.closeButton)
                .setOnClickListener(v -> {
                    mainView.removeView(dpad2);
                    dpad2 = null;
                });

        dpad2.animate().x(x).y(y)
                .setDuration(200)
                .start();
    }

    private void addKey(String key, Float x ,Float y) {
        KeyX.add(i,new FloatingActionKey(this));

        mainView.addView(KeyX.get(i));

        KeyX.get(i).setText(key);

        KeyX.get(i).animate()
                .x(x)
                .y(y)
                .setDuration(1000)
                .start();

        KeyX.get(i).setOnClickListener(this::setKeyInFocus);
        i++;
    }

    private void setKeyInFocus(View view){
       KeyInFocus = ((FloatingActionKey)view);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (KeyInFocus != null) {
            String key = String.valueOf(event.getDisplayLabel());
            if ( key.matches("[a-zA-Z0-9]+" )) {
                KeyInFocus.setText(key);
            }
        }
        return true;
    }
}
