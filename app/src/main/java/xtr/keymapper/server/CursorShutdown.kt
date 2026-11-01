package xtr.keymapper.server

import xtr.keymapper.log.XtLogger

internal object CursorShutdown {
    private const val TAG = "xtmapper-server"

    fun hideThenCleanup(hideCursor: () -> Unit, cleanup: () -> Unit) {
        try {
            hideCursor()
        } catch (e: RuntimeException) {
            XtLogger.warn(TAG, "Client Binder unavailable while hiding cursor; continuing cleanup", e)
        }
        cleanup()
    }
}
