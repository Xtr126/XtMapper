package xtr.keymapper.editor;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.appcompat.view.ContextThemeWrapper;

import xtr.keymapper.R;
import xtr.keymapper.keymap.KeymapConfig;

public class ShowKeymapService extends Service {
    private EditorUI editorUi;

    public static void start(Context context, String selectedProfile) {
        Intent intent = new Intent(context, ShowKeymapService.class);
        intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile);
        context.startService(intent);
    }

    @Override
    public void onDestroy() {
        editorUi.hideView();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (editorUi != null) editorUi.hideView();

        String selectedProfile = intent.getStringExtra(EditorActivity.PROFILE_NAME);

        if (selectedProfile == null) {
            selectedProfile = "Default";
        }
        KeymapConfig keymapConfig = new KeymapConfig(this);
        Context context = new ContextThemeWrapper(this, R.style.Theme_XtMapper);
        editorUi = new EditorUI(context, editorCallback, selectedProfile, EditorUI.SHOW_KEYMAP_ONLY);
        editorUi.loadKeymapAfterView();
        editorUi.showControls(keymapConfig.showControlsOpacity);
        return super.onStartCommand(intent, flags, startId);
    }

    private final EditorCallback editorCallback = () -> {
        editorUi = null;
    };


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}