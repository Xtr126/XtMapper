package xtr.keymapper

import android.os.SystemProperties

object Environment {
    internal const val LEGACY_CAGE_ARG = "--cage-xtmapper-launch-signal"

    private val waydroid by lazy {
        detectWaydroid { property ->
            try {
                SystemProperties.get(property, "")
            } catch (_: LinkageError) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    internal fun detectWaydroid(read: (String) -> String?): Boolean {
        val evidence = listOf("waydroid.tools_version", "waydroid.xdg_runtime_dir", "waydroid.wayland_display")
        return evidence.count { property ->
            val value = read(property)?.trim().orEmpty()
            value.isNotEmpty() && !value.equals("None", ignoreCase = true)
        } >= 2
    }

    @JvmStatic
    val isWaydroid: Boolean get() = waydroid

    @JvmStatic
    val isAndroid: Boolean get() = !isWaydroid

    @JvmStatic
    val name: String get() = when {
        isWaydroid -> "waydroid"
        else -> "android"
    }
}
