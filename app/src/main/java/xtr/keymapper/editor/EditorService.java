package xtr.keymapper.editor;

 import android.app.Service;
 import android.content.Context;
import android.content.Intent;
 import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.appcompat.view.ContextThemeWrapper;

import xtr.keymapper.R;
 import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.server.RemoteServiceHelper;

public class EditorService extends Service {
    private EditorUI editor;

    private final EditorUI.OnHideListener onHideListener = new EditorUI.OnHideListener() {
        @Override
        public void onHideView() {
            RemoteServiceHelper.getInstance(EditorService.this, service -> {
                try {
                    service.unregisterOnKeyEventListener(editor);
                    service.resumeMouse();
                    service.reloadKeymap();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            });
            editor = null;
            stopSelf();
        }

        @Override
        public boolean getEvent() {
            return true;
        }
    };


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String selectedProfile = intent.getStringExtra(EditorActivity.PROFILE_NAME);
        if (selectedProfile == null) {
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        KeymapConfig keymapConfig = new KeymapConfig(this);
        if (keymapConfig.editorOverlay) {
            Context context = new ContextThemeWrapper(EditorService.this, R.style.Theme_XtMapper);
            editor = new EditorUI(context, onHideListener, selectedProfile, EditorUI.START_EDITOR);

            RemoteServiceHelper.getInstance(EditorService.this, remoteService -> {
                try {
                    if (editor != null) {
                        remoteService.registerOnKeyEventListener(editor);
                        remoteService.pauseMouse();
                    }
                } catch (RemoteException e) {
                    Log.e("editorActivity", e.getMessage(), e);
                }
            });

            editor.open(true);
        } else {
            Intent newIntent = new Intent(this, EditorActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            newIntent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile);
            startActivity(newIntent);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}