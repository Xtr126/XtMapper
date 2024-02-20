package xtr.keymapper.editor;

import static xtr.keymapper.keymap.KeymapProfiles.MOUSE_RIGHT;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.R;
import xtr.keymapper.databinding.CrosshairBinding;
import xtr.keymapper.databinding.DpadArrowsBinding;
import xtr.keymapper.databinding.DpadWasdBinding;
import xtr.keymapper.databinding.KeymapEditorBinding;
import xtr.keymapper.databinding.ResizableBinding;
import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.dpad.Dpad.DpadType;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.floatingkeys.MovableFrameLayout;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.KeymapProfileKey;
import xtr.keymapper.keymap.KeymapProfiles;
import xtr.keymapper.mouse.MouseAimConfig;
import xtr.keymapper.mouse.MouseAimSettings;
import xtr.keymapper.server.RemoteServiceHelper;
import xtr.keymapper.swipekey.SwipeKey;
import xtr.keymapper.swipekey.SwipeKeyView;

public class EditorUI extends OnKeyEventListener.Stub {

    private final LayoutInflater layoutInflater;
    private final ViewGroup mainView;

    private MovableFloatingActionKey keyInFocus;
    // Keyboard keys
    private final List<MovableFloatingActionKey> keyList = new ArrayList<>();
    private final List<SwipeKeyView> swipeKeyList = new ArrayList<>();
    private MovableFloatingActionKey leftClick, rightClick;

    private MovableFrameLayout dpadWasd, dpadUdlr, crosshair;
    // Default position of new views added
    private final KeymapEditorBinding binding;
    private final Context context;
    private final OnHideListener onHideListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final String profileName;
    private KeymapProfile profile;

    public EditorUI (Context context, String profileName) {
        this.context = context;
        this.onHideListener = ((OnHideListener) context);
        this.profileName = profileName;

        layoutInflater = context.getSystemService(LayoutInflater.class);

        binding = KeymapEditorBinding.inflate(layoutInflater);
        mainView = binding.getRoot();

        binding.speedDial.inflate(R.menu.keymap_editor_menu);
        binding.speedDial.open();
        setupButtons();
    }

    public void open() {
        loadKeymap();
        if (mainView.getWindowToken() == null && mainView.getParent() == null)
            ((EditorActivity)context).setContentView(mainView);

        if (!onHideListener.getEvent()) {
            mainView.setOnKeyListener(this::onKey);
            mainView.setFocusable(true);
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (keyInFocus != null) {
            String key = String.valueOf(event.getDisplayLabel());
            if ( key.matches("[a-zA-Z0-9]+" )) {
                keyInFocus.setText(key);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onKeyEvent(String event) {
        // line: /dev/input/event3 EV_KEY KEY_X DOWN
        String[] input_event = event.split("\\s+");
        String code = input_event[2];

        // Ignore non key events
        if(!input_event[1].equals("EV_KEY") || !code.contains("KEY_")) return;

        // Incoming calls are not guaranteed to be executed on the main thread
        mHandler.post(() -> {
            if (keyInFocus != null)
                keyInFocus.setText(input_event[2].substring(4));
        });
    }


    public interface OnHideListener {
        void onHideView();
        boolean getEvent();
    }

    public void hideView() {
        saveKeymap();
        removeView(mainView);
        onHideListener.onHideView();
    }

    private void removeView(ViewGroup view) {
        view.removeAllViews();
        view.invalidate();
    }

    private void loadKeymap() {
        profile = new KeymapProfiles(context).getProfile(profileName);
        // Add Keyboard keys as Views
        profile.keys.forEach(this::addKey);
        profile.swipeKeys.forEach(swipeKey -> swipeKeyList.add(new SwipeKeyView(mainView, swipeKey, swipeKeyList::remove, this::onClick)));

        if (profile.dpadUdlr != null) addArrowKeysDpad(profile.dpadUdlr.getX(), profile.dpadUdlr.getY());

        if (profile.dpadWasd != null) addWasdDpad(profile.dpadWasd.getX(), profile.dpadWasd.getY());

        if (profile.mouseAimConfig != null) addCrosshair(profile.mouseAimConfig.xCenter, profile.mouseAimConfig.yCenter);
        if (profile.rightClick != null) addRightClick(profile.rightClick.x, profile.rightClick.y);
    }

    private void saveKeymap() {
        ArrayList<String> linesToWrite = new ArrayList<>();

        if (dpadWasd != null) {
            Dpad dpad = new Dpad(dpadWasd, DpadType.WASD);
            linesToWrite.add(dpad.getData());
        }

        if (dpadUdlr != null) {
            Dpad dpad = new Dpad(dpadUdlr, DpadType.UDLR);
            linesToWrite.add(dpad.getData());
        }

        if (crosshair != null) {
            // Get x and y coordinates from view
            profile.mouseAimConfig.setCenterXY(crosshair);
            profile.mouseAimConfig.setLeftClickXY(leftClick);
            linesToWrite.add(profile.mouseAimConfig.getData());
        }

        if (rightClick != null) {
            linesToWrite.add(MOUSE_RIGHT + " " + rightClick.getX() + " " + rightClick.getY());
        }
        
        // Keyboard keys
        keyList.stream().map(MovableFloatingActionKey::getData).forEach(linesToWrite::add);

        swipeKeyList.stream().map(swipeKeyView -> new SwipeKey(swipeKeyView).getData()).forEach(linesToWrite::add);

        // Save Config
        KeymapProfiles profiles = new KeymapProfiles(context);
        profiles.saveProfile(profileName, linesToWrite, profile.packageName, !profile.disabled);

        // Reload keymap if service running
        RemoteServiceHelper.reloadKeymap(context);
    }

    public void setupButtons() {
        binding.speedDial.setOnActionSelectedListener(actionItem -> {
            // X y coordinates of center of root view
            float defaultX = mainView.getPivotX();
            float defaultY = mainView.getPivotY();

            int id = actionItem.getId();

            if (id == R.id.add) {
                addKey(defaultX, defaultY);
            }
            else if (id == R.id.save) {
                hideView();
            }
            else if (id == R.id.dpad) {
                final CharSequence[] items = { "Arrow Keys", "WASD Keys"};
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Select Dpad").setItems(items, (dialog, i) -> {
                    if (i == 0) addArrowKeysDpad(defaultX, defaultY);
                    else addWasdDpad(defaultX, defaultY);
                });
                AlertDialog dialog = builder.create();
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                dialog.show();
            }
            else if (id == R.id.crosshair) {
                profile.mouseAimConfig = new MouseAimConfig();
                addCrosshair(defaultX, defaultY);
            }
            else if (id == R.id.mouse_left) {
                addLeftClick(defaultX, defaultY);
            }
            else if (id == R.id.swipe_key) {
                addSwipeKey();
            }
            else if (id == R.id.mouse_right) {
                addRightClick(defaultX, defaultY);
            }
            return true;
        });
    }

    private void addWasdDpad(float x, float y) {
        if (dpadWasd == null) {
            DpadWasdBinding binding = DpadWasdBinding.inflate(layoutInflater, mainView, true);
            dpadWasd = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(dpadWasd);
                dpadWasd = null;
            });
            binding.resizeHandle.setOnTouchListener(new ResizeableDpadView(dpadWasd));
        }
        moveResizeDpad(dpadWasd, profile.dpadWasd, x, y);
    }

    private void addArrowKeysDpad(float x, float y) {
        if (dpadUdlr == null) {
            DpadArrowsBinding binding = DpadArrowsBinding.inflate(layoutInflater, mainView, true);
            dpadUdlr = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(dpadUdlr);
                dpadUdlr = null;
            });
            binding.resizeHandle.setOnTouchListener(new ResizeableDpadView(dpadUdlr));
        }
        moveResizeDpad(dpadUdlr, profile.dpadUdlr, x, y);
    }

    private void moveResizeDpad(ViewGroup dpadLayout, Dpad dpad, float x, float y) {
        dpadLayout.animate().x(x).y(y)
                .setDuration(500)
                .start();

        if (dpad != null) {
            // resize dpad from saved profile configuration
            float x1 = dpad.getWidth() - dpadLayout.getLayoutParams().width;
            float y1 = dpad.getHeight() - dpadLayout.getLayoutParams().height;
            resizeView(dpadLayout, x1, y1);
        }
    }

    private void addKey(KeymapProfileKey key) {
        MovableFloatingActionKey floatingKey = new MovableFloatingActionKey(context, keyList::remove);

        floatingKey.setText(key.code.substring(4));
        floatingKey.animate()
                .x(key.x)
                .y(key.y)
                .setDuration(1000)
                .start();
        floatingKey.setOnClickListener(this::onClick);

        mainView.addView(floatingKey);
        keyList.add(floatingKey);
    }

    private void addKey(float x, float y) {
        final KeymapProfileKey key = new KeymapProfileKey();
        key.code = "KEY_X";
        key.x = x;
        key.y = y;
        addKey(key);
    }

    public void onClick(View view) {
        keyInFocus = ((MovableFloatingActionKey)view);
    }

    private void addCrosshair(float x, float y) {
        if (crosshair == null) {
            CrosshairBinding binding = CrosshairBinding.inflate(layoutInflater, mainView, true);
            crosshair = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(crosshair);
                crosshair = null;
            });
            binding.expandButton.setOnClickListener(v -> new ResizableArea());
            binding.editButton.setOnClickListener(v -> new MouseAimSettings().getDialog(context).show());
        }
        crosshair.animate().x(x).y(y)
                .setDuration(500)
                .start();

        addLeftClick(profile.mouseAimConfig.xleftClick,
                     profile.mouseAimConfig.yleftClick);
    }

    private void addLeftClick(float x, float y) {
        if (leftClick == null) {
            leftClick = new MovableFloatingActionKey(context);
            leftClick.key.setImageResource(R.drawable.ic_baseline_mouse_36);
            mainView.addView(leftClick);
        }
        leftClick.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void addRightClick(float x, float y) {
        if (rightClick == null) {
            rightClick = new MovableFloatingActionKey(context, key -> rightClick = null);
            rightClick.key.setImageResource(R.drawable.ic_baseline_mouse_36);
            mainView.addView(rightClick);
        }
        rightClick.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void addSwipeKey() {
        SwipeKeyView swipeKeyView = new SwipeKeyView(mainView, swipeKeyList::remove, this::onClick);
        swipeKeyList.add(swipeKeyView);
    }

    public static void resizeView(View view, float x, float y) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        layoutParams.width += x;
        layoutParams.height += y;
        view.requestLayout();
    }

    class ResizableArea implements View.OnTouchListener, View.OnClickListener {
        private final ViewGroup rootView;
        private float defaultPivotX, defaultPivotY;

        @SuppressLint("ClickableViewAccessibility")
        public ResizableArea(){
            ResizableBinding binding1 = ResizableBinding.inflate(layoutInflater, mainView, true);
            rootView = binding1.getRoot();
            binding1.dragHandle.setOnTouchListener(this);
            binding1.saveButton.setOnClickListener(this);
            moveView();
        }

        private void getDefaultPivotXY(){
            defaultPivotX = rootView.getPivotX();
            defaultPivotY = rootView.getPivotY();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                resizeView(rootView, event.getX(), event.getY());
                // Resize View from center point
                if (defaultPivotX > 0) {
                    float newPivotX = rootView.getPivotX() - defaultPivotX;
                    float newPivotY = rootView.getPivotY() - defaultPivotY;
                    rootView.setX(rootView.getX() - newPivotX);
                    rootView.setY(rootView.getY() - newPivotY);
                }
                getDefaultPivotXY();
            } else
                v.performClick();
            return true;
        }
        @Override
        public void onClick(View v) {
            float x = rootView.getX() + rootView.getPivotX();
            float y = rootView.getY() + rootView.getPivotY();
            crosshair.setX(x);
            crosshair.setY(y);
            profile.mouseAimConfig.width = rootView.getPivotX();
            profile.mouseAimConfig.height = rootView.getPivotY();

            mainView.removeView(rootView);
            rootView.invalidate();
        }
        private void moveView(){
            float x = crosshair.getX() - crosshair.getWidth();
            float y = crosshair.getY() - crosshair.getHeight();
            rootView.setX(x);
            rootView.setY(y);
        }
    }
}
