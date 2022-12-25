package xtr.keymapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import com.nambimobile.widgets.efab.ExpandableFabLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.KeymapConfig;
import xtr.keymapper.aim.MouseAimKey;
import xtr.keymapper.aim.MouseAimSettings;
import xtr.keymapper.databinding.CrosshairBinding;
import xtr.keymapper.databinding.Dpad1Binding;
import xtr.keymapper.databinding.Dpad2Binding;
import xtr.keymapper.databinding.KeymapEditorBinding;
import xtr.keymapper.databinding.ResizableBinding;
import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.floatingkeys.MovableFrameLayout;

public class EditorUI implements View.OnKeyListener {

    private final WindowManager.LayoutParams mParams;
    private final WindowManager mWindowManager;
    private final LayoutInflater layoutInflater;
    private final ExpandableFabLayout mainView;

    private MovableFloatingActionKey KeyInFocus;
    private final List<MovableFloatingActionKey> Keys = new ArrayList<>();

    private MovableFrameLayout dpad1, dpad2, crosshair;
    private MouseAimKey mouseAimKey;
    private static final Float DEFAULT_X = 200f, DEFAULT_Y = 200f;
    private int i = 0;
    private final KeymapEditorBinding binding;
    private final Context context;

    public EditorUI (Context context) {
        this.context = context;
        layoutInflater = context.getSystemService(LayoutInflater.class);
        mWindowManager = context.getSystemService(WindowManager.class);
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.CENTER;

        binding = KeymapEditorBinding.inflate(layoutInflater);
        mainView = binding.getRoot();
        setupButtons();
    }

    public void open() {
        try {
            loadKeymap();
        } catch (Exception e) {
            Log.d("Error1", e.toString());
        }
        if (mainView.getWindowToken() == null) {
            if (mainView.getParent() == null) {
                mWindowManager.addView(mainView, mParams);
                mainView.setOnKeyListener(this);
                mainView.setFocusable(true);
            }
        }
    }

    public void hideView() {
        try {
            saveKeymap();
            mWindowManager.removeView(mainView);
            mainView.invalidate();
            // remove all views
            ((ViewGroup) mainView.getParent()).removeAllViews();
            // the above steps are necessary when you are adding and removing
            // the view simultaneously, it might give some exceptions
        } catch (Exception e) {
            Log.d("Error2", e.toString());
        }
    }

    private void loadKeymap() throws IOException {

        KeymapConfig keymapConfig = new KeymapConfig(context);
        keymapConfig.loadConfig();

        String[] keys = keymapConfig.getKeys();
        Float[] keysX = keymapConfig.getX();
        Float[] keysY = keymapConfig.getY();

        for (int n = 0; n < keys.length; n++) {
            if (keys[n] != null) {
                addKey(keys[n], keysX[n], keysY[n]);
            }
        }

        Dpad dpad1 = keymapConfig.dpad1;
        Dpad dpad2 = keymapConfig.dpad2;
        mouseAimKey = keymapConfig.mouseAimKey;

        if (dpad1 != null) {
            addDpad1(dpad1.getX(), dpad1.getY());
        }

        if (dpad2 != null) {
            addDpad2(dpad2.getX(), dpad2.getY());
        }

        if (mouseAimKey != null) {
            addCrosshair(mouseAimKey.x, mouseAimKey.y);
        }
    }

    private void saveKeymap() throws IOException {
        StringBuilder linesToWrite = new StringBuilder();

        if (dpad1 != null) {
            Dpad dpad = new Dpad(dpad1, Dpad.TYPE_UDLR);
            linesToWrite.append(dpad.getData());
        }

        if (dpad2 != null) {
            Dpad dpad = new Dpad(dpad2, Dpad.TYPE_WASD);
            linesToWrite.append(dpad.getData());

            for (int i = 0; i < Keys.size(); i++) {
                String key = Keys.get(i).getText();
                if(key.matches("[WASD]")) {
                    Keys.get(i).key = null; // If WASD keys already added, remove them
                }
            }
        }

        if (crosshair != null) {
            mouseAimKey.setXY(crosshair);
            linesToWrite.append(mouseAimKey.getData());
        }

        for (int i = 0; i < Keys.size(); i++) {
            if(Keys.get(i).key != null) {
                linesToWrite.append(Keys.get(i).getData());
            }
        }

        KeymapConfig keymapConfig = new KeymapConfig(context);
        keymapConfig.writeConfig(linesToWrite);
    }

    public void setupButtons() {
        binding.saveButton.setOnClickListener(v -> hideView());
        binding.addButton.setOnClickListener(v -> addKey("A", DEFAULT_X, DEFAULT_Y));
        binding.crossHair.setOnClickListener(v -> {
            mouseAimKey = new MouseAimKey();
            addCrosshair(DEFAULT_X, DEFAULT_Y);
        });

        binding.dPad.setOnClickListener(new View.OnClickListener() {
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

        binding.dPad.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        binding.crossHair.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        binding.saveButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        binding.addButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }

    private void addDpad1(Float x, Float y) {
        if (dpad1 == null) {
            Dpad1Binding binding = Dpad1Binding.inflate(layoutInflater, mainView, true);
            dpad1 = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(dpad1);
                dpad1 = null;
            });
        }
        dpad1.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void addDpad2(Float x, Float y) {
        if (dpad2 == null) {
            Dpad2Binding binding = Dpad2Binding.inflate(layoutInflater, mainView, true);
            dpad2 = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(dpad2);
                dpad2 = null;
            });
        }
        dpad2.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void addKey(String key, Float x ,Float y) {
        Keys.add(i,new MovableFloatingActionKey(context));

        mainView.addView(Keys.get(i));

        Keys.get(i).setText(key);

        Keys.get(i).animate()
                .x(x)
                .y(y)
                .setDuration(1000)
                .start();

        Keys.get(i).setOnClickListener(this::setKeyInFocus);
        i++;
    }

    private void addCrosshair(Float x, Float y) {
        if (crosshair == null) {
            CrosshairBinding binding = CrosshairBinding.inflate(layoutInflater, mainView, true);
            crosshair = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(crosshair);
                crosshair = null;
            });
            binding.expandButton.setOnClickListener(v -> new ResizableLayout());
            binding.editButton.setOnClickListener(v -> new MouseAimSettings().getDialog(context).show());
        }
        crosshair.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void setKeyInFocus(View view){
       KeyInFocus = ((MovableFloatingActionKey)view);
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (KeyInFocus != null) {
            String key = String.valueOf(event.getDisplayLabel());
            if ( key.matches("[a-zA-Z0-9]+" )) {
                KeyInFocus.setText(key);
                return true;
            }
        }
        return false;
    }


    class ResizableLayout implements View.OnTouchListener {
        private final View view;

        @SuppressLint("ClickableViewAccessibility")
        public ResizableLayout(){
            ResizableBinding binding1 = ResizableBinding.inflate(layoutInflater, mainView, true);
            view = binding1.getRoot();
            binding1.dragHandle.setOnTouchListener(this);
            binding1.saveButton.setOnClickListener(v -> {
                mainView.removeView(view);
                mouseAimKey.width = view.getPivotX();
                mouseAimKey.height = view.getPivotY();
            });
            moveView();
        }
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
                layoutParams.width += x;
                layoutParams.height += y;
                moveView();
            } else {
                v.performClick();
            }
            return true;
        }
        private void moveView(){
            float x = crosshair.getX() - view.getPivotX();
            float y = crosshair.getY() - view.getPivotY();
            view.setX(x);
            view.setY(y);
            view.requestLayout();
        }
    }
}
