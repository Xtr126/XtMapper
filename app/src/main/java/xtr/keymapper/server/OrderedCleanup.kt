package xtr.keymapper.server

import xtr.keymapper.log.XtLogger

/**
 * Failure-isolated teardown: every step runs even when an earlier step throws.
 * Exceptions are logged and swallowed so one failing native release cannot skip
 * the remaining ones; any non-Exception Throwable is rethrown after all steps
 * have run so the existing fatal handler still terminates the process.
 */
internal object OrderedCleanup {
    private const val TAG = "xtmapper-server"

    fun run(vararg steps: Pair<String, () -> Unit>) {
        var firstThrowable: Throwable? = null
        for ((name, step) in steps) {
            try {
                step()
            } catch (e: Exception) {
                XtLogger.error(TAG, "cleanup step failed: $name", e)
            } catch (t: Throwable) {
                if (firstThrowable == null) firstThrowable = t
                else firstThrowable.addSuppressed(t)
            }
        }
        firstThrowable?.let { throw it }
    }
}
