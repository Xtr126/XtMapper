package xtr.keymapper.editor;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import xtr.keymapper.server.RemoteServiceHelper;

public class ShowKeymapService extends Service {
    private EditorUI editorUi;

    /**
     * @param intent  The Intent supplied to {@link android.content.Context#startService},
     *                as given.  This may be null if the service is being restarted after
     *                its process has gone away, and it had previously returned anything
     *                except {@link #START_STICKY_COMPATIBILITY}.
     * @param flags   Additional data about this start request.
     * @param startId A unique integer representing this specific request to
     *                start.  Use with {@link #stopSelfResult(int)}.
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String selectedProfile = intent.getStringExtra(EditorActivity.PROFILE_NAME);

        if (selectedProfile == null) {
            selectedProfile = "Default";
        }

        editorUi = new EditorUI(this, null, selectedProfile, EditorUI.SHOW_KEYMAP_ONLY);
        editorUi.loadKeymap();
        editorUi.showControls();
        return super.onStartCommand(intent, flags, startId);
    }

    private final EditorCallback editorCallback = new EditorCallback() {
        @Override
        public void onHideView() {
            editorUi = null;
            stopSelf();
        }

        @Override
        public boolean getEvent() {
            return false;
        }
    };


    public ShowKeymapService() {

    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}