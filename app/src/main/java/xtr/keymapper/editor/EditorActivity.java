package xtr.keymapper.editor;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.R;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.server.RemoteServiceHelper;

public class EditorActivity extends Activity {
    public static final String PROFILE_NAME = "profile";
    private EditorUI editor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String selectedProfile = getIntent().getStringExtra(PROFILE_NAME);
        if (selectedProfile == null) {
            finish();
            return;
        }

        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());

        if (editor != null) editor.hideView();

        setTheme(R.style.Theme_XtMapper);

        editor = new EditorUI(this, this::editorCallback, selectedProfile, EditorUI.START_EDITOR);

        KeymapConfig keymapConfig = new KeymapConfig(this);
        if (Settings.canDrawOverlays(this)) {
            editor.open(keymapConfig.editorOverlay);
        } else {
            editor.open(false);
            MainActivity.checkOverlayPermission(this);
        }

        RemoteServiceHelper.getInstance(this, this::onConnection);
    }

    private void editorCallback() {
        editor = null;
        finish();
    }

    private void onConnection(IRemoteService service) {
        if (editor != null) {
            KeymapConfig keymapConfig = new KeymapConfig(this);

            if (service != null)
                // Can receive key events from remote service
                try {
                    editor.registerOnKeyEventListener(service);
                } catch (RemoteException e) {
                    Log.e("editorActivity", e.getMessage(), e);
                }
            else {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);

                builder.setMessage(R.string.dialog_alert_editor)
                        .setPositiveButton(R.string.ok, (dialog, which) -> {})
                        .setTitle(R.string.dialog_alert_editor_title);
                AlertDialog dialog = builder.create();
                if (keymapConfig.editorOverlay) dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                dialog.show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (editor != null) editor.hideView();
        editor = null;
    }
}