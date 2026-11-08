package xtr.keymapper.server

import xtr.keymapper.log.XtLogger

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
