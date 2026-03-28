package xtr.keymapper.editor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.R;
import xtr.keymapper.Utils;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.editor.component.Camera;
import xtr.keymapper.editor.component.Crosshair;
import xtr.keymapper.editor.component.MouseWalk;
import xtr.keymapper.editor.component.RightClick;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.KeymapProfiles;
import xtr.keymapper.macro.MacroIdUtils;
import xtr.keymapper.macro.MacroStatus;
import xtr.keymapper.macro.MacroView;
import xtr.keymapper.server.RemoteServiceHelper;

public class EditorUI extends OnKeyEventListener.Stub {
    // When a keyboard key is pressed,
    private KeyInFocusListener keyInFocus;

    private final Context context;

    private final EditorCallback editorCallback;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final String profileName;

    private KeymapProfile profile;
    private KeymapProfile profileBackup;

    private boolean overlayOpen = false;

    private final SettingsOverlay settingsOverlay;

    private final ViewGroup mainView;
    private final ViewGroup keysContainerView;

    /* Start modes */
    public static final int START_SETTINGS = 0;
    public static final int START_EDITOR = 1;
    public static final int SHOW_KEYMAP_ONLY = 2;
    private final int startMode;

    private MacroDialog macroDialog = null;

    private final EditorUiComponentList editorUiComponents = new EditorUiComponentList();

    private IRemoteService mService;

    public void unregisterOnKeyEventListener() {
        settingsOverlay.onUnRegisterKeyEventListener();
        if (mService != null) {
            try {
                mService.unregisterOnKeyEventListener(this);
                mService.resumeMouse();
                mService.reloadKeymap();
            } catch (RemoteException ignored) {
            }
        }
        mService = null;
    }

    public void registerOnKeyEventListener(IRemoteService service) throws RemoteException {
        mService = service;
        mService.registerOnKeyEventListener(this);
        settingsOverlay.onRegisterKeyEventListener();
        mService.pauseMouse();
    }

    public interface KeyInFocusListener {
        void setText(String key);
    }

    public EditorUI (Context context, EditorCallback editorCallback, String profileName, int startMode) {
        this.context = context;
        this.editorCallback = editorCallback;
        this.profileName = profileName;
        this.startMode = startMode;

        LayoutInflater layoutInflater = context.getSystemService(LayoutInflater.class);

        if (startMode != SHOW_KEYMAP_ONLY) {
            context.stopService(new Intent(context, ShowKeymapService.class));
            settingsOverlay = new SettingsOverlay(context, startMode);
            mainView = settingsOverlay.createView(layoutInflater);
            keysContainerView = settingsOverlay.binding.keyContainer;

            settingsOverlay.inflateMenuResource(startMode, layoutInflater);
            settingsOverlay.setOnActionSelectedListener(this::onActionSelected);
        } else {
            keysContainerView = new FrameLayout(context);
            keysContainerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            settingsOverlay = null;
            mainView = null;
        }
    }

    public void open(boolean overlayWindow) {
        settingsOverlay.overlayWindow = overlayWindow;
        if (mainView.getWindowToken() == null && mainView.getParent() == null)
            if (overlayWindow) openOverlayWindow();
            else {
                overlayOpen = false;
                if (context instanceof EditorActivity)
                    ((Activity)context).setContentView(mainView);
                else // For MainActivity
                    ((Activity)context).addContentView(mainView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

        if (mService == null) {
            mainView.setOnKeyListener(this::onKey);
            mainView.setFocusable(true);
        }

        loadKeymapAfterView();
    }

    public void openSettings() {
        ((MainActivity)context).addContentView(mainView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openOverlayWindow() {
        openOverlayWindow(-1);
    }

    void showControls(float alpha) {
        openOverlayWindow(alpha);
    }

    private void openOverlayWindow(float alpha) {
        ViewGroup overlayView = startMode == SHOW_KEYMAP_ONLY ? keysContainerView : mainView;
        if (overlayOpen) {
            removeView(overlayView);
        }

        WindowManager mWindowManager = context.getSystemService(WindowManager.class);
        WindowManager.LayoutParams mParams = startMode == SHOW_KEYMAP_ONLY ? Utils.getPointerLayoutParams(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) : new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT);


        if (alpha < 1 && alpha > 0)
            overlayView.setAlpha(alpha);

        mWindowManager.addView(overlayView, mParams);
        overlayOpen = true;
        if(startMode != SHOW_KEYMAP_ONLY) hideSystemBars();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ViewGroup overlayView = startMode == SHOW_KEYMAP_ONLY ? keysContainerView : mainView;
            WindowInsetsController windowInsetsController = overlayView.getWindowInsetsController();
            if (windowInsetsController != null) {
                windowInsetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            }
        }
    }

    /**
     * For events received by view
     * @return true if we consume the event
     */
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

    /**
     * For key events received from getevent running in remote process
     * @param event A line of output from getevent -ql
     */
    @Override
    public void onKeyEvent(String event) {
        // line: /dev/input/event3 EV_KEY KEY_X DOWN
        String[] input_event = event.split("\\s+");
        String code = input_event[2];

        // Ignore non key events
        if(!input_event[1].equals("EV_KEY") || !code.contains("KEY_")) return;
        String key = input_event[2].substring(4);

        // Incoming calls are not guaranteed to be executed on the main thread
        mHandler.post(() -> {
            if (macroDialog != null) macroDialog.onKey(key);
            else if (keyInFocus != null) keyInFocus.setText(key);
            settingsOverlay.onKey(key);
        });

    }


    /**
     * Called when a button in catalog has been clicked.
     *
     * @param id the relevant id of the menu item for the card.
     */
    public void onActionSelected(int id) {
        // X y coordinates of center of root view
        float defaultX = mainView.getPivotX();
        float defaultY = mainView.getPivotY();

        if (id == R.id.save) {
            hideView();
        } else if (id == R.id.macro) {
            showMacroDialog();
        } else if (id == R.id.reset) {
            profile = profileBackup;
            reloadKeymap();
        } else {
            editorUiComponents.addMatchingComponentForId(id, mCallback, context, defaultX, defaultY, editorUiComponents::add);
        }
    }

    private void showMacroDialog() {
        macroDialog = new MacroDialog(context, overlayOpen, profile);
        macroDialog.show(v -> {
            if (v != null) addMacro();
            if (macroDialog != null) {
                macroDialog.dismiss();
                macroDialog = null;
            }
       });
    }

    /**
     * MacroStatus for displaying elapsed time
     * Stop when receiving any keyboard input from User or stopwatch is clicked and show macro dialog finally
     * MacroView for visualization
     */
    private void addMacro() {

        if (mService != null) {
            mainView.setFocusable(true);
        }
        MacroStatus macroStatus = new MacroStatus(context, settingsOverlay.binding.catalog);
        MacroView macroView = new MacroView(context, (macroView_) -> {
            // Stop counting time in stopwatch
            macroStatus.stop();

            // Remove the macro view
            keysContainerView.removeView(macroView_);
            macroView_.invalidate();

            // Redirect keyboard input
            mainView.setOnKeyListener(EditorUI.this::onKey);
            settingsOverlay.unHideButtons();
            if (mService == null) mainView.setFocusable(false);

            showMacroDialog();
        });
        // Hide existing buttons in catalog till macro finish
        settingsOverlay.hideButtons();
        macroStatus.start();

        keysContainerView.addView(macroView);
        // Redirect keyboard input
        mainView.setOnKeyListener((v, keyCode, event) -> macroView.onKey(event));

        settingsOverlay.binding.catalog.setOnClickListener(v -> macroView.clearCanvasAndFinish());
    }

    public void hideView() {
        if (startMode == START_EDITOR) saveKeymap();
        if (startMode != SHOW_KEYMAP_ONLY) {
            unregisterOnKeyEventListener();
            settingsOverlay.onDestroyView();

            removeView(mainView);
            if (editorCallback != null) editorCallback.onHideView();
            else RemoteServiceHelper.reloadKeymap(context);

            // Load keymap config after settingsOverlay wrote config
            KeymapConfig keymapConfig = new KeymapConfig(context);
            // We stopped the service, so we must restart it
            RemoteServiceHelper.runIfActive(context, () -> {
                if (keymapConfig.showControls)
                    ShowKeymapService.start(context, profileName);
            });

        } else removeView(keysContainerView);
    }

    private void removeView(ViewGroup view) {
        if (overlayOpen && view.isAttachedToWindow()) context.getSystemService(WindowManager.class).removeView(view);
        view.removeAllViews();
        view.invalidate();
    }

    void loadKeymapAfterView() {
        profileBackup = new KeymapProfiles(context).getProfile(profileName, false);
        keysContainerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right != oldRight || left != oldLeft || top != oldTop || bottom != oldBottom) {
                if (profile != null) {
                    // Save previous state before wiping
                    saveKeymap();
                    reloadKeymap();
                }
            }
        });
        keysContainerView.post(this::loadKeymap);
    }

    private void reloadKeymap() {
        keysContainerView.removeAllViews();
        editorUiComponents.clear();
        loadKeymap();
    }

    private void loadKeymap() {

        profile = new KeymapProfiles(context).getProfile(profileName, false);

        // Scale to current display size
        profile.scale(keysContainerView.getWidth(), keysContainerView.getHeight());

        EditorUiComponentList.Factory editorUiComponentFactory = editorUiComponents.newFactory(mCallback, context);

        editorUiComponentFactory.addKeys(profile.keys);
        editorUiComponentFactory.addSwipeKeys(profile.swipeKeys);
        editorUiComponentFactory.addDpads(profile.dpadArray);

        if (profile.mouseAimConfig != null) {
            editorUiComponents.add(new Crosshair(mCallback, context, profile.mouseAimConfig));
        }
        if (profile.rightClick != null) {
            editorUiComponents.add(new RightClick(mCallback, context, profile.rightClick));
        }
        if (profile.camera != null) {
            editorUiComponents.add(new Camera(mCallback, context, profile.camera.x, profile.camera.y));
        }
        if (profile.mouseWalk != null) {
            editorUiComponents.add(new MouseWalk(mCallback, context, profile.mouseWalk));
        }
    }


    private void saveKeymap() {
        ArrayList<String> linesToWrite = new ArrayList<>();

        editorUiComponents.stream()
                .map(EditorUiComponent::getDataLine)
                .forEach(linesToWrite::add);

        // Enabled macro ids
        MacroIdUtils.getLines(linesToWrite, profile);

        // Save Config
        KeymapProfiles profiles = new KeymapProfiles(context);

        profiles.saveProfile(profileName, linesToWrite, profile.packageName, !profile.disabled,
                keysContainerView.getWidth(), keysContainerView.getHeight());
    }

    private final EditorUiComponentCallback mCallback = new EditorUiComponentCallback() {
        @Override
        public boolean isOverlayOpen() {
            return overlayOpen;
        }

        @Override
        public KeymapProfile getProfile() {
            return profile;
        }

        @Override
        public ViewGroup getKeysContainerView() {
            return keysContainerView;
        }

        @Override
        public void removeComponent(EditorUiComponent component, View... viewsToRemove) {
            for (View view : viewsToRemove) {
                keysContainerView.removeView(view);
            }
            editorUiComponents.remove(component);
        }

        @Override
        public void setOnKeyListener(KeyInFocusListener keyInFocusListener) {
            keyInFocus = keyInFocusListener;
        }
    };

    public static void resizeView(View view, int x, int y) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        layoutParams.width += x;
        layoutParams.height += y;
        view.requestLayout();
    }

}