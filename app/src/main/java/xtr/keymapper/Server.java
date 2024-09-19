package xtr.keymapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.CharBuffer;

import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.server.RemoteServiceShell;

public class Server {

    private static void writeScript(StringBuffer linesToWrite, File scriptFile) throws IOException, InterruptedException {
        FileWriter fileWriter = new FileWriter(scriptFile);
        FileReader fileReader = new FileReader(scriptFile);

        CharBuffer target = CharBuffer.allocate((int) scriptFile.length());
        fileReader.read(target);

        // Write script to disk only if file contents are not the same.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (linesToWrite.compareTo(new StringBuffer(target)) != 0) {
                fileWriter.write(linesToWrite.toString());
            }
        } else {
            if (!linesToWrite.toString().equals(target.toString()))
                fileWriter.write(linesToWrite.toString());
        }
        fileWriter.close();
        fileReader.close();
    }

    private static @NonNull StringBuffer generateScript(ApplicationInfo ai) {
        final String className = RemoteServiceShell.class.getName();
        StringBuffer linesToWrite = new StringBuffer();
        linesToWrite.append("#!/system/bin/sh\n");
        linesToWrite.append("pkill -f ").append(className).append("\n");
        linesToWrite.append("exec /system/bin/app_process");
        linesToWrite.append(" -Djava.library.path=\"").append(ai.nativeLibraryDir)  //path containing lib*.so
                .append("\" -Djava.class.path=\"").append(ai.publicSourceDir) // Absolute path to apk in /data/app
                .append("\" / ").append(className)
                .append(" \"$@\" \n");
        return linesToWrite;
    }

    public static void setupServer(Context context, MainActivity.Callback mCallback) {
        File script = new File(context.getExternalFilesDir(null), "xtMapper.sh");
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            writeScript(generateScript(ai), script);
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException ex) {
            Log.e("Server", ex.toString());
            mCallback.updateCmdView1("failed to write script: " + ex);
        }
        if (!script.exists()) mCallback.updateCmdView1("failed to write script: permission denied");
    }

}
