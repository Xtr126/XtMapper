package xtr.keymapper

import android.os.Build

/**
 * Best-effort identification of the Android environment the process runs in.
 *
 * Waydroid (Linux container running LineageOS-based images) puts `waydroid`
 * into the build fingerprint and device/product names on stock images, e.g.
 * `waydroid/lineage_waydroid_x86_64/waydroid_x86_64:11/RQ3A...` — verified in
 * upstream crash reports across Android 10-13, x86 and x86_64 images.
 *
 * shortcut: string-based only. Installs that spoof their identity via
 * `/var/lib/waydroid/waydroid.cfg [properties]` (the community
 * spoof-device.sh rewrites 20+ props incl. the fingerprint) are NOT detected.
 * Upgrade path if false negatives ever matter: additionally read
 * `/proc/version` and look for a host-Linux distro string, which the
 * container leaks regardless of prop spoofing. Not worth it today.
 */
object Environment {

    /** True when running inside a Waydroid Android container (stock images). */
    @JvmStatic
    val isWaydroid: Boolean =
        Build.FINGERPRINT.contains("waydroid", ignoreCase = true) ||
            Build.DEVICE.startsWith("waydroid", ignoreCase = true)

    /** Short label for diagnostics: `waydroid` or `android`. */
    @JvmStatic
    val name: String get() = if (isWaydroid) "waydroid" else "android"
}
