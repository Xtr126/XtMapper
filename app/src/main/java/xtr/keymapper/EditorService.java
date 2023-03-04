package xtr.keymapper;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import xtr.keymapper.profiles.ProfileSelector;
import xtr.keymapper.server.InputService;

public class EditorService extends Service implements EditorUI.OnHideListener {
    private EditorUI editor;
    private IRemoteService mService;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mService = InputService.getInstance();

        if (editor != null) editor.hideView();

        ProfileSelector.select(this, profile -> {
            editor = new EditorUI(this, profile);
            editor.open();

            if (mService != null)
                try {
                    mService.registerOnKeyEventListener(editor);
                } catch (RemoteException ignored) {
                }
            else {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(new ContextThemeWrapper(this, R.style.Theme_XtMapper));

                builder.setMessage(R.string.dialog_alert_editor)
                        .setPositiveButton("Ok", (dialog, which) -> {})
                        .setTitle(R.string.dialog_alert_editor_title);
                AlertDialog dialog = builder.create();
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                dialog.show();
            }
        });

        return super.onStartCommand(intent, flags, startId);
    }


    @Override
    public void onHideView() {
        try {
            mService.unregisterOnKeyEventListener(editor);
        } catch (RemoteException ignored) {
        }
        editor = null;
        stopSelf();
    }

    @Override
    public boolean getEvent() {
        return mService != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}