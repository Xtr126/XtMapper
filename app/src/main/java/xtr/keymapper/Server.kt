package xtr.keymapper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.AtomicFile
import android.util.Log
import xtr.keymapper.server.RemoteServiceShell
import xtr.keymapper.wayland.WaylandLauncher
import java.io.File
import java.io.FileWriter
import java.io.IOException

object Server {

    internal fun prepareWaydroidCommand(context: Context): String {
        val script = AtomicFile(File(context.filesDir, "start-waydroid.sh"))
        val contents = generateWaydroidScript(context.packageName).toByteArray()
        val stream = script.startWrite()
        try {
            stream.write(contents)
            script.finishWrite(stream)
        } catch (e: IOException) {
            script.failWrite(stream)
            throw e
        }
        if (!script.readFully().contentEquals(contents)) throw IOException("Waydroid launcher was not written")
        return "sudo waydroid shell -- sh ${shellQuote(script.baseFile.absolutePath)}"
    }

    internal fun generateWaydroidScript(packageName: String): String {
        val launchClass = WaylandLauncher::class.java.name
        return """
            #!/system/bin/sh
            set -eu
            fail() { echo "XtMapper: ${'$'}*" >&2; exit 1; }

            pkg=${shellQuote(packageName)}
            apk=${'$'}(pm path "${'$'}pkg" | sed -n '1s/^package://p')
            info=${'$'}(dumpsys package "${'$'}pkg")
            lib_base=${'$'}(printf '%s\n' "${'$'}info" | sed -n 's/^[[:space:]]*legacyNativeLibraryDir=//p' | head -n 1)
            abi=${'$'}(printf '%s\n' "${'$'}info" | sed -n 's/^[[:space:]]*primaryCpuAbi=//p' | head -n 1)
            case "${'$'}abi" in
                arm64-v8a) isa=arm64 ;;
                armeabi-v7a|armeabi) isa=arm ;;
                x86|x86_64) isa=${'$'}abi ;;
                *) fail "The installed APK has an unsupported native architecture: ${'$'}abi" ;;
            esac
            [ -f "${'$'}apk" ] || fail "The installed APK for ${'$'}pkg is unavailable."
            [ -n "${'$'}lib_base" ] && [ -f "${'$'}lib_base/${'$'}isa/libxtmapper_wayland.so" ] ||
                fail "The installed native libraries for ${'$'}pkg are unavailable."
            lib=${'$'}lib_base/${'$'}isa

            # The directory inode stays stable when the app regenerates the script.
            exec 9<"${'$'}{0%/*}"
            flock -n 0 <&9 || fail "The Waydroid server is already running."

            # A fresh Waydroid shell may inherit an incomplete classpath. Read the ordered
            # framework and APEX classpath that the running image supplied to zygote.
            set -- ${'$'}(pidof zygote64 zygote || true)
            [ "${'$'}#" -gt 0 ] || fail "Android has not finished starting."
            boot_classpath=${'$'}(tr '\000' '\n' < "/proc/${'$'}1/environ" | sed -n 's/^BOOTCLASSPATH=//p')
            [ -n "${'$'}boot_classpath" ] || fail "Could not read Android's boot classpath."
            old_ifs=${'$'}IFS
            IFS=:
            for jar in ${'$'}boot_classpath; do
                [ -r "${'$'}jar" ] || fail "Android boot classpath entry is unavailable: ${'$'}jar"
            done
            IFS=${'$'}old_ifs
            env "BOOTCLASSPATH=${'$'}boot_classpath" /system/bin/app_process \
                "-Djava.class.path=${'$'}apk" "-Djava.library.path=${'$'}lib" / $launchClass "${'$'}apk" "${'$'}lib" --no-logcat 9<&-
        """.trimIndent() + "\n"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun writeScript(linesToWrite: StringBuffer, scriptFile: File) {
        // Always rewrite: the old read-compare ran after FileWriter truncated the file, so it never matched
        FileWriter(scriptFile).use { it.write(linesToWrite.toString()) }
    }

    private fun generateScript(ai: ApplicationInfo): StringBuffer {
        val className = RemoteServiceShell::class.java.name
        return StringBuffer().apply {
            append("#!/system/bin/sh\n")
            append("if [ ! -e \"").append(ai.nativeLibraryDir).append("\" ]; then\n")
            append("  echo 'Error: native library path does not exist' >&2\n")
            append("  exit 1\n")
            append("fi\n")
            append("if [ ! -e \"").append(ai.publicSourceDir).append("\" ]; then\n")
            append("  echo 'Error: APK path does not exist' >&2\n")
            append("  exit 1\n")
            append("fi\n")
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
