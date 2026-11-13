package xtr.keymapper

import android.app.Application
import xtr.keymapper.log.XtLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        XtLogger.init(this)
        XtLogger.info("App", "application process started (version ${BuildConfig.VERSION_NAME})")
    }
}
