package xtr.keymapper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.view.Window;

import androidx.appcompat.view.ContextThemeWrapper;

public class EditorService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Context context = new ContextThemeWrapper(this, R.style.Theme_MaterialComponents);
        EditorUI editor = new EditorUI(context);
        editor.open();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}