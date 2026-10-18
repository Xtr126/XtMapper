package xtr.keymapper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import xtr.keymapper.server.RemoteServiceShell
import java.io.File
import java.io.FileWriter
import java.io.IOException

object Server {

    private fun writeScript(linesToWrite: StringBuffer, scriptFile: File) {
        // Always rewrite: the old read-compare ran after FileWriter truncated the file, so it never matched
        FileWriter(scriptFile).use { it.write(linesToWrite.toString()) }
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
