package xtr.keymapper.server

import android.util.Log
import android.os.SystemClock
import xtr.keymapper.BuildConfig

internal object OverlayPermission {
    private const val TAG = "xtmapper-permission"

    fun grant(): Boolean {
        val process = try {
            ProcessBuilder(
                "appops",
                "set",
                BuildConfig.APPLICATION_ID,
                "SYSTEM_ALERT_WINDOW",
                "allow",
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start appops to grant overlay access", e)
            return false
        }

        var exitCode: Int? = null
        try {
            val deadline = SystemClock.elapsedRealtime() + PROCESS_TIMEOUT_MILLIS
            while (exitCode == null && SystemClock.elapsedRealtime() < deadline) {
                exitCode = try {
                    process.exitValue()
                } catch (_: IllegalThreadStateException) {
                    null
                }
                if (exitCode == null) Thread.sleep(PROCESS_POLL_INTERVAL_MILLIS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            Log.w(TAG, "Interrupted while setting SYSTEM_ALERT_WINDOW app-op", e)
            return false
        }
        if (exitCode == null) {
            process.destroy()
            Log.w(TAG, "Timed out setting SYSTEM_ALERT_WINDOW app-op")
            return false
        }

        val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            .getOrDefault("")
        val granted = exitCode == 0
        if (granted) {
            Log.i(TAG, "Granted SYSTEM_ALERT_WINDOW app-op")
        } else {
            Log.w(TAG, "appops could not grant SYSTEM_ALERT_WINDOW: ${output.trim()}")
        }
        return granted
    }

    private const val PROCESS_TIMEOUT_MILLIS = 5_000L
    private const val PROCESS_POLL_INTERVAL_MILLIS = 25L
}
