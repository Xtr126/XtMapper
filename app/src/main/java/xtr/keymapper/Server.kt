package xtr.keymapper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import xtr.keymapper.server.RemoteServiceShell
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.nio.CharBuffer

object Server {

    private fun writeScript(linesToWrite: StringBuffer, scriptFile: File) {
        val fileWriter = FileWriter(scriptFile)
        val fileReader = FileReader(scriptFile)

        val target = CharBuffer.allocate(scriptFile.length().toInt())
        fileReader.read(target)

        // Write script to disk only if file contents are not the same.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (linesToWrite.compareTo(StringBuffer(target)) != 0) {
                fileWriter.write(linesToWrite.toString())
            }
        } else {
            if (linesToWrite.toString() != target.toString())
                fileWriter.write(linesToWrite.toString())
        }
        fileWriter.close()
        fileReader.close()
    }

    private fun generateScript(ai: ApplicationInfo): StringBuffer {
        val className = RemoteServiceShell::class.java.name
        return StringBuffer().apply {
            append("#!/system/bin/sh\n")
            append("pkill -f ").append(className).append("\n")
            append("exec /system/bin/app_process")
            append(" -Djava.library.path=\"").append(ai.nativeLibraryDir) //path containing lib*.so
            append("\" -Djava.class.path=\"").append(ai.publicSourceDir) // Absolute path to apk in /data/app
            append("\" / ").append(className)
            append(" \"$@\" \n")
        }
    }

    fun setupServer(context: Context, mCallback: TouchPointer.MainActivityCallback) {
        val script = File(context.getExternalFilesDir(null), "xtMapper.sh")
        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val ai = pm.getApplicationInfo(packageName, 0)
            writeScript(generateScript(ai), script)
        } catch (ex: Exception) {
            // Java multi-catch equivalent: only these three types were handled
            if (ex !is IOException && ex !is InterruptedException && ex !is PackageManager.NameNotFoundException) throw ex
            Log.e("Server", ex.toString())
            mCallback.updateCmdView1("failed to write script: $ex")
        }
        if (!script.exists()) mCallback.updateCmdView1("failed to write script: permission denied")
    }
}
