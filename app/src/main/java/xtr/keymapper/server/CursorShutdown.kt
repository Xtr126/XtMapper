package xtr.keymapper.server

import xtr.keymapper.log.XtLogger

/**
 * Local cursor-overlay shutdown, decoupled from client-Binder liveness.
 *
 * The overlay view lives in the service process, so it must be hidden even after the client
 * Binder dies. [InputService.hideCursor] hides that overlay without any Binder call when the
 * overlay exists; only the system-pointer fallback (no overlay) calls disablePointer() over
 * Binder and may throw a RuntimeException wrapping DeadObjectException. This helper always
 * attempts the hide, swallows that Binder failure, and then runs the remaining cleanup
 * unconditionally so the native mouse reader and resources are never left running.
 */
internal object CursorShutdown {
    private const val TAG = "xtmapper-server"

    fun hideThenCleanup(hideCursor: () -> Unit, cleanup: () -> Unit) {
        try {
            hideCursor()
        } catch (e: RuntimeException) {
            // The overlay hide is local; a throw here means the system-pointer fallback hit
            // a dead client Binder. Cleanup must still run.
            XtLogger.warn(TAG, "Client Binder unavailable while hiding cursor; continuing cleanup", e)
        }
        cleanup()
    }
}
