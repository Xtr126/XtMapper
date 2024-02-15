package xtr.keymapper.editor;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.R;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.profiles.ProfileSelector;
import xtr.keymapper.server.RemoteService;

public class EditorActivity extends Activity implements EditorUI.OnHideListener {
    private EditorUI editor;
    private IRemoteService mService;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
//        RemoteServiceHelper.pauseKeymap();
        mService = RemoteService.getInstance();

        if (editor != null) editor.hideView();
        bindService(new Intent(this, TouchPointer.class), connection, Context.BIND_AUTO_CREATE);
    }

    ProfileSelector.OnProfileSelectedListener listener = profile -> {
        editor = new EditorUI(this, profile);
        editor.open();

        if (getEvent())
            // Can receive key events from remote service
            try {
                mService.registerOnKeyEventListener(editor);
            } catch (RemoteException e) {
                Log.e("editorActivity", e.getMessage(), e);
            }
        else {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(new ContextThemeWrapper(this, R.style.Theme_XtMapper));

            builder.setMessage(R.string.dialog_alert_editor)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {})
                    .setTitle(R.string.dialog_alert_editor_title);
            AlertDialog dialog = builder.create();
            dialog.show();
        }
    };

    /** Defines callbacks for service binding, passed to bindService() */
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            TouchPointer.TouchPointerBinder binder = (TouchPointer.TouchPointerBinder) service;
            TouchPointer pointerOverlay = binder.getService();
            if (pointerOverlay.selectedProfile != null) {
                // service is active and a profile is selected
                listener.onProfileSelected(pointerOverlay.selectedProfile);
            } else {
                listener.onProfileSelected(null);
                // service is not active, show profile selection dialog
                ProfileSelector.select(EditorActivity.this, listener);
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };

    @Override
    public void onHideView() {
       onDestroy();
       finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (getEvent()) try {
            mService.unregisterOnKeyEventListener(editor);
        } catch (RemoteException ignored) {
        }
        editor = null;
//        RemoteServiceHelper.resumeKeymap();
    }

    @Override
    public boolean getEvent() {
        return mService != null;
    }
}