package xtr.keymapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.server.RemoteService;

public class Server {

    public File script;
    public MainActivity.Callback mCallback;

    static {
        // Set settings before the main shell can be created
        Shell.enableVerboseLogging = false;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        );
    }

    private void writeScript(ApplicationInfo ai) throws IOException, InterruptedException {
        final String className = RemoteService.class.getName();

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

    private final List<String> callbackList = new CallbackList<>() {
        @Override
        public void onAddElement(String line) {
            mCallback.updateCmdView1("stdout: " + line + "\n");
            if (line.equals("Waiting for overlay..."))
                mCallback.alertActivation();
        }
    };

    public void startServer() {
        mCallback.updateCmdView1("exec sh " + script.getPath() + "\n");
        Shell.getShell(shell -> {
            if (!shell.isRoot()) mCallback.alertRootAccessNotFound();
            else try {
                Shell.cmd(new FileInputStream(script)).to(callbackList)
                        .submit(out -> System.exit(1));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        });
    }

}
