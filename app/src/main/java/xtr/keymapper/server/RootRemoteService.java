package xtr.keymapper.server;

import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.ipc.RootService;

class RootRemoteService extends RootService {
    static {
        // Only load the library when this class is loaded in a root process.
        // The classloader will load this class (and call this static block) in the non-root
        // process because we accessed it when constructing the Intent to send.
        // Add this check so we don't unnecessarily load native code that'll never be used.
        if (Process.myUid() == 0)
            RemoteService.loadLibraries();
    }

    private RemoteService mService = null;

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        if (mService == null) {
            mService = new RemoteService().init(this);
        }
        return mService;
    }

}