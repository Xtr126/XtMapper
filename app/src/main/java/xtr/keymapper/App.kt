package xtr.keymapper

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Main application class responsible for initializing global runtime exemptions.
 */
class App : Application() {
    /**
     * Initializes HiddenApiBypass exemptions on Android 9+ prior to component initialization.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t: Throwable) {
                Log.w("App", "Failed to add hidden API exemptions", t)
            }
        }
    }
}
