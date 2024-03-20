package xtr.keymapper.editor;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import xtr.keymapper.TouchPointer;

public class EditorService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        bindService(new Intent(this, TouchPointer.class), connection, Context.BIND_AUTO_CREATE);
        return super.onStartCommand(intent, flags, startId);
    }

    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            TouchPointer.TouchPointerBinder binder = (TouchPointer.TouchPointerBinder) service;
            TouchPointer pointerOverlay = binder.getService();
            try {
                pointerOverlay.mCallback.launchEditor();
            } catch (RemoteException ignored) {
            }
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