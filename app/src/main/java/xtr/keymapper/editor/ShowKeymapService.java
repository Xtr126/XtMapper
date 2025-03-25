package xtr.keymapper.editor;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public class ShowKeymapService extends Service {
    private EditorUI editorUi;

    public static void start(Context context, String selectedProfile) {
        Intent intent = new Intent(context, ShowKeymapService.class);
        intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String selectedProfile = intent.getStringExtra(EditorActivity.PROFILE_NAME);

        if (selectedProfile == null) {
            selectedProfile = "Default";
        }

        editorUi = new EditorUI(this, editorCallback, selectedProfile, EditorUI.SHOW_KEYMAP_ONLY);
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


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}