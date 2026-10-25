package xtr.keymapper

/**
 * Launch-context classification for a running XtMapper process.
 *
 * The only classification signal is the explicit Cage launch handshake:
 * [CLI_ARG] on the shell entry point command line and the boolean [INTENT_EXTRA]
 * (`am start --ez`) delivered to MainActivity. Signal present → `waydroid`;
 * signal absent or false → `android`. Absence intentionally selects the Android
 * classification — it does not independently prove a physical device.
 *
 * No Build-string fallback: fingerprint/device matching was spoofable and was
 * deliberately removed; heuristics must not override the signal.
 *
 * shortcut: the APP-role session header renders inside App.onCreate, before any
 * activity intent can exist, so a process cold-started with the signal logs the
 * `android` default in its SESSION START block and only reclassifies when
 * MainActivity applies the extra (MainActivity logs that application). Ceiling:
 * no supported channel delivers intent extras before Application.onCreate;
 * upgrade path is a pre-Application signal carrier if the header itself must be
 * right from the first byte.
 */
object Environment {

    /** Command-line argument accepted by [xtr.keymapper.server.RemoteServiceShell]. */
    const val CLI_ARG = "--cage-xtmapper-launch-signal"

    /** Boolean intent extra carrying the same signal to MainActivity. */
    const val INTENT_EXTRA = "cage_xtmapper_launch_signal"

    /**
     * Explicit launch signal state. Default false: an untouched process
     * classifies as Android.
     */
    @JvmStatic
    @Volatile
    var launchSignal: Boolean = false

    @JvmStatic
    val isWaydroid: Boolean get() = launchSignal

    @JvmStatic
    val isAndroid: Boolean get() = !launchSignal

    @JvmStatic
    val name: String get() = when {
        isWaydroid -> "waydroid"
        else -> "android"
    }
}
