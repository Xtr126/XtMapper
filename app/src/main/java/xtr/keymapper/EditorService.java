package xtr.keymapper;

import android.app.AlertDialog;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.WindowManager;
import android.widget.ArrayAdapter;

import androidx.appcompat.view.ContextThemeWrapper;

import java.util.ArrayList;

import xtr.keymapper.profiles.KeymapProfiles;
import xtr.keymapper.profiles.ProfileSelector;
import xtr.keymapper.server.InputService;

public class EditorService extends Service implements EditorUI.OnHideListener {
    private EditorUI editor;
    private IRemoteService mService;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mService = InputService.getInstance();

        if (editor != null) editor.hideView();

        new ProfileSelector(this, profile -> {
            editor = new EditorUI(this, profile);
            editor.open();

            if (mService != null)
                try {
                    mService.registerOnKeyEventListener(editor);
                } catch (RemoteException ignored) {
                }
            else {
                AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.Theme_Material3_DayNight_Dialog_Alert));

                builder.setMessage(R.string.dialog_alert_editor)
                        .setPositiveButton("ok", (dialog, which) -> {})
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