package xtr.keymapper

import android.app.Application
import xtr.keymapper.log.XtLogger

/**
 * Application hook for the logging subsystem.
 *
 * `onCreate` runs before every component of the main process (launcher activity,
 * display selector, foreground service, input method service and the in-process
 * system-app server) so all of them share the same session and the same one
 * writer instance. It stays non-interactive: an absent folder grant simply keeps
 * file logging unavailable (Logcat only) and the user enables it from the main
 * screen. No uncaught-exception handler is installed or changed here.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        XtLogger.init(this)
        XtLogger.info("App", "application process started (version ${BuildConfig.VERSION_NAME})")
    }
}
