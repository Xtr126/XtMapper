package xtr.keymapper

object Environment {

    const val CLI_ARG = "--cage-xtmapper-launch-signal"

    const val INTENT_EXTRA = "cage_xtmapper_launch_signal"

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
