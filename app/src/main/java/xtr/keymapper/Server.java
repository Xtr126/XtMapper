package xtr.keymapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.server.InputService;

public class Server {

    public File script;
    public MainActivity.Callback mCallback;

    private void writeScript(ApplicationInfo ai) throws IOException, InterruptedException {
        final String className = InputService.class.getName();

        FileWriter linesToWrite = new FileWriter(script, false);
        linesToWrite.append("#!/system/bin/sh\n");
        linesToWrite.append("pgrep -f ").append(className).append(" && echo Waiting for overlay... && exit 1\n");
        linesToWrite.append("exec env ");
        linesToWrite.append("LD_LIBRARY_PATH=\"").append(ai.nativeLibraryDir)  //path containing lib*.so
                .append("\" CLASSPATH=\"").append(ai.publicSourceDir) // Absolute path to apk in /data/app
                .append("\" /system/bin/app_process /system/bin ")
                .append(className).append("\n");

        linesToWrite.flush();
        linesToWrite.close();
    }

    public void setupServer (Context context) {
        try {
            script = new File(context.getExternalFilesDir(null), "xtMapper.sh");
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            writeScript(ai);
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException ex) {
            Log.e("Server", ex.toString());
            mCallback.updateCmdView1("failed to write script: " + ex + "\n");
        }
        if (!script.exists()) mCallback.updateCmdView1("failed to write script: permission denied\n");
    }

    public void startServer() {
        mCallback.updateCmdView1("exec sh " + script.getPath() + "\n");
        try {
            Process sh = Utils.getRootAccess();
            DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("/system/bin/sh " + script.getPath());
            outputStream.close();

            BufferedReader stdout = new BufferedReader(new InputStreamReader(sh.getInputStream()));
            String line;
            while ((line = stdout.readLine()) != null) {
                mCallback.updateCmdView1("stdout: " + line + "\n");
                if (line.equals("Waiting for overlay..."))
                    mCallback.startPointer();
                else mCallback.alertRootAccessNotFound();
            }
            if (sh.waitFor(1, TimeUnit.SECONDS)) mCallback.alertRootAccessNotFound();
            sh.destroy();
        } catch (IOException | InterruptedException ex) {
            Log.e("Server", ex.toString());
        }
    }

}
