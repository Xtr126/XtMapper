package xtr.keymapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-rule tests for Waydroid detection.
 *
 * Evidence tiers:
 *  - VERBATIM: stock fingerprints copied from upstream Waydroid crash reports
 *    (waydroid issues #38, #340, #2143, SO #78942933) — Android 10/11, x86/x86_64.
 *  - PATTERN:  fingerprints built from the documented image naming used by the
 *    official OTA channels (`lineage_waydroid_<arch>`, all four architectures;
 *    Android 13-16 bases) — the substring rule is identical, but these exact
 *    strings are extrapolated from the OTA naming, not crash-log evidence.
 *
 * CI therefore validates the rule against the full architecture/version
 * matrix without needing a running Waydroid container per combination.
 */
class EnvironmentTest {

    // ---------------------------------------------------------------- VERBATIM

    /** Verbatim stock fingerprints from upstream crash reports. */
    private val verbatimFingerprints = listOf(
        // waydroid #38 — Android 10 x86_64
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:10/QQ3A.200805.001/eng.bot.20210910:userdebug/test-keys",
        // waydroid #340 — Android 11 x86_64
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:11/RQ3A.211001.001/eng.root.20220129:userdebug/test-keys",
        // waydroid #2143 — Android 11 x86_64 on Debian
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:11/RQ3A.211001.001/eng.root.20220129:userdebug/test-keys",
        // SO #78942933 — 32-bit x86 variant
        "waydroid/lineage_waydroid_x86/waydroid_x86:11/RQ3A.211001.001/97:userdebug/test-keys",
    )

    @Test
    fun `verbatim crash-report fingerprints are detected`() {
        for (fp in verbatimFingerprints) {
            assertTrue("should detect $fp", matchesWaydroid(fp, "generic_x86_64"))
        }
    }

    // ----------------------------------------------------------------- PATTERN

    /**
     * All four official architectures x the Android versions covered by
     * official mainline (13) and community (11, 14, 15, 16, 17-exp) images.
     * The fingerprint layout is the LineageOS standard the OTA channels use
     * for every arch: waydroid/lineage_waydroid_<arch>/waydroid_<arch>:<api>/...
     */
    private val patternFingerprints = listOf(
        // arch: x86_64 (PC, laptop, Steam Deck)
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:11/RQ3A.211001.001/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:13/TP1A.220624.014/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:14/UP1A.231005.007/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:15/AP3A.240905.015/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:16/AQ3A.250105.002/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:17/BP11.250101.001/eng.builder:userdebug/test-keys",
        // arch: arm64 / aarch64 (Linux phones, tablets, SBCs, Raspberry Pi)
        "waydroid/lineage_waydroid_arm64/waydroid_arm64:13/TP1A.220624.014/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_arm64/waydroid_arm64:14/UP1A.231005.007/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_arm64/waydroid_arm64:15/AP3A.240905.015/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_arm64/waydroid_arm64:16/AQ3A.250105.002/eng.builder:userdebug/test-keys",
        // arch: x86 (32-bit Intel/AMD)
        "waydroid/lineage_waydroid_x86/waydroid_x86:11/RQ3A.211001.001/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_x86/waydroid_x86:13/TP1A.220624.014/eng.builder:userdebug/test-keys",
        // arch: arm (32-bit ARM)
        "waydroid/lineage_waydroid_arm/waydroid_arm:13/TP1A.220624.014/eng.builder:userdebug/test-keys",
        "waydroid/lineage_waydroid_arm/waydroid_arm:14/UP1A.231005.007/eng.builder:userdebug/test-keys",
    )

    @Test
    fun `pattern fingerprints across all four architectures and android versions are detected`() {
        for (fp in patternFingerprints) {
            assertTrue("should detect $fp", matchesWaydroid(fp, "generic"))
        }
    }

    @Test
    fun `device names for every architecture are detected on their own`() {
        // ro.product.device = waydroid_<arch> (from ro.product.odm.device).
        for (arch in listOf("x86_64", "arm64", "x86", "arm")) {
            val device = "waydroid_$arch"
            assertTrue("device $device", matchesWaydroid("lineage/$device/$device:13/...", device))
            // Even with a fully spoofed (non-waydroid) fingerprint, the odm
            // device name alone still trips detection.
            assertTrue("device-only $device", matchesWaydroid("google/redfin/redfin:13/TP1A...", device))
        }
    }

    @Test
    fun `gapps and atv image variants keep the waydroid naming`() {
        // GAPPS builds and WayDroid-ATV community builds (Android 15/17) use
        // the same lineage_waydroid_<arch> channel naming.
        assertTrue(matchesWaydroid(
            "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:15/AP3A.240905.015/eng.builder:userdebug/test-keys",
            "generic_x86_64"))
        assertTrue(matchesWaydroid(
            "waydroid/lineage_waydroid_x86_64/waydroid_x86_64:17/BP11.250101.001/eng.builder:eng/test-keys",
            "generic_x86_64"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(matchesWaydroid("Waydroid/Lineage_waydroid_x86_64", "generic"))
        assertTrue(matchesWaydroid("generic", "WAYDROID_X86_64"))
    }

    // --------------------------------------------------------------- NEGATIVES

    @Test
    fun `real device fingerprints are not detected`() {
        // SM-A155F (physical test device) and a Pixel 5 stock fingerprint.
        assertFalse(matchesWaydroid(
            "samsung/a15xx/a15:16/AP3A.240905.015/A155FXXU9BYA1:user/release-keys", "a15"))
        assertFalse(matchesWaydroid(
            "google/redfin/redfin:11/RQ3A.211001.001/eng.electr.20230318.111310:user/release-keys", "redfin"))
    }

    @Test
    fun `other android containers and emulators are not misdetected as waydroid`() {
        // Redroid (docker Android) keeps AOSP defaults; AVD uses goldfish/ranchu.
        assertFalse(matchesWaydroid(
            "generic/arm64-ab/arm64:13/TP1A.220624.014/userdebug/test-keys", "generic_arm64"))
        assertFalse(matchesWaydroid(
            "google/sdk_gphone64_x86_64/emulator64_x86_64:13/TE1A.220922.012/userdebug/test-keys",
            "sdk_gphone64_x86_64"))
    }

    @Test
    fun `spoofed waydroid identities that drop waydroid strings are not detected`() {
        // spoof-device.sh rewrites fingerprint+device to a Pixel 5 identity —
        // the documented ceiling of this string-based check (see shortcut:).
        assertFalse(matchesWaydroid(
            "google/redfin/redfin:11/RQ3A.211001.001/eng.electr.20230318.111310:user/release-keys",
            "redfin"))
    }

    @Test
    fun `android label is used when not waydroid`() {
        assertEquals("android",
            if (matchesWaydroid("samsung/a15xx", "a15")) "waydroid" else "android")
    }
}
