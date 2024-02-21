package xtr.keymapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import xtr.keymapper.activity.MainActivity;

public class Server {

    private static void writeScript(ApplicationInfo ai, File script) throws IOException, InterruptedException {
        final String className = xtr.keymapper.server.RemoteServiceShell.class.getName();

        FileWriter linesToWrite = new FileWriter(script, false);
        linesToWrite.append("#!/system/bin/sh\n");
        linesToWrite.append("pkill -f ").append(className).append("\n");
        linesToWrite.append("exec /system/bin/app_process");
        linesToWrite.append(" -Djava.library.path=\"").append(ai.nativeLibraryDir)  //path containing lib*.so
                .append("\" -Djava.class.path=\"").append(ai.publicSourceDir) // Absolute path to apk in /data/app
                .append("\" / ").append(className)
                .append(" \"$@\" \n");

        linesToWrite.flush();
        linesToWrite.close();
    }

    public static void setupServer(Context context, MainActivity.Callback mCallback) {
        File script = new File(context.getExternalFilesDir(null), "xtMapper.sh");
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            writeScript(ai, script);
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException ex) {
            Log.e("Server", ex.toString());
            mCallback.updateCmdView1("failed to write script: " + ex + "\n");
        }
        if (!script.exists()) mCallback.updateCmdView1("failed to write script: permission denied\n");
    }

}
