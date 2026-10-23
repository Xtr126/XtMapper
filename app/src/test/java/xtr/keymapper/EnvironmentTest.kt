package xtr.keymapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-rule tests for Waydroid detection. The stock fingerprints are
 * verbatim from upstream Waydroid crash reports (waydroid issues #38, #340,
 * #2143 and SO #78942933), so CI validates the rule against evidence without
 * needing a running Waydroid container.
 */
class EnvironmentTest {

    // Verbatim stock fingerprints from upstream crash reports.
    private val stockFingerprints = listOf(
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
    fun `stock waydroid fingerprints are detected`() {
        for (fp in stockFingerprints) {
            assertTrue("should detect $fp", matchesWaydroid(fp, "generic_x86_64"))
        }
    }

    @Test
    fun `waydroid device name alone is detected`() {
        // Init logs: ro.product.device = waydroid_x86_64 (from ro.product.odm.device).
        assertTrue(matchesWaydroid("lineage/waydroid_x86_64/...", "waydroid_x86_64"))
        assertTrue(matchesWaydroid("lineage/waydroid_arm64/...", "waydroid_arm64"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(matchesWaydroid("Waydroid/Lineage_waydroid_x86_64", "generic"))
    }

    @Test
    fun `real device fingerprints are not detected`() {
        // SM-A155F (physical test device) and a Pixel 5 stock fingerprint.
        assertFalse(matchesWaydroid(
            "samsung/a15xx/a15:16/AP3A.240905.015/A155FXXU9BYA1:user/release-keys", "a15"))
        assertFalse(matchesWaydroid(
            "google/redfin/redfin:11/RQ3A.211001.001/eng.electr.20230318.111310:user/release-keys", "redfin"))
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
        assertEquals("android", if (matchesWaydroid("samsung/a15xx", "a15")) "waydroid" else "android")
    }
}
