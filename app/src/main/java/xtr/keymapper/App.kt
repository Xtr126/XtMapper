package xtr.keymapper

import android.app.Application
import xtr.keymapper.log.XtLogger

/**
 * Application hook for the logging subsystem: `onCreate` runs before every
 * component of the main process, so they all share one session and one writer.
 * It stays non-interactive: without a folder grant file logging stays off
 * (Logcat only) until the user enables it from the main screen.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        XtLogger.init(this)
        XtLogger.info("App", "application process started (version ${BuildConfig.VERSION_NAME})")
    }
}
