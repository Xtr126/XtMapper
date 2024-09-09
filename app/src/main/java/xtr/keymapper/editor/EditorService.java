package xtr.keymapper.editor;

 import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
 import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.appcompat.view.ContextThemeWrapper;

import xtr.keymapper.R;
import xtr.keymapper.TouchPointer;
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
        Log.i(this.getClass().getName(), "Launching editor");
        KeymapConfig keymapConfig = new KeymapConfig(this);
        if (keymapConfig.editorOverlay) {
            bindService(new Intent(this, TouchPointer.class), connection, Context.BIND_AUTO_CREATE);
        } else {
            Intent newIntent = new Intent(getApplicationContext(), EditorActivity.class);
            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(newIntent);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            TouchPointer.TouchPointerBinder binder = (TouchPointer.TouchPointerBinder) service;
            TouchPointer pointerOverlay = binder.getService();

            Context context = new ContextThemeWrapper(EditorService.this, R.style.Theme_XtMapper);
            editor = new EditorUI(context, onHideListener, pointerOverlay.selectedProfile);

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
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}