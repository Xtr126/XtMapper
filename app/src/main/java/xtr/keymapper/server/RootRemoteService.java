package xtr.keymapper.server;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

    public final RemoteService mService = new RemoteService();


    @Override
    public IBinder onBind(@NonNull Intent intent) {
        PackageManager pm = this.getPackageManager();
        String packageName = this.getPackageName();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            mService.nativeLibraryDir = ai.nativeLibraryDir;
            mService.start_getevent();
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        return mService;
    }

}