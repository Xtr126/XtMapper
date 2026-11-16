package xtr.keymapper.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupLaunchTest {

    @Test
    fun authenticated_shell_launch_autostarts_default_when_the_profile_exists() {
        assertEquals("Default", StartupLaunch.autoStartProfile(true, setOf("Default", "Game")))
    }

    @Test
    fun authenticated_shell_launch_without_a_default_profile_stays_manual() {
        assertNull(StartupLaunch.autoStartProfile(true, setOf("Game")))
        assertNull(StartupLaunch.autoStartProfile(true, emptySet()))
    }

    @Test
    fun unauthenticated_launch_never_autostarts_even_with_a_default_profile() {
        assertNull(StartupLaunch.autoStartProfile(false, setOf("Default")))
    }

    @Test
    fun shell_startup_profile_survives_recreation_until_overlay_grant() {
        val originalActivity = PendingShellStartup("Default")
        var startedProfile: String? = null

        assertFalse(originalActivity.startIfOverlayGranted(false) { startedProfile = it })
        assertNull(startedProfile)

        val recreatedActivity = PendingShellStartup(originalActivity.profileName)
        assertEquals("Default", recreatedActivity.profileName)
        assertTrue(recreatedActivity.startIfOverlayGranted(true) { startedProfile = it })
        assertEquals("Default", startedProfile)
        assertNull(recreatedActivity.profileName)
        assertFalse(recreatedActivity.startIfOverlayGranted(true) { startedProfile = it })
    }
}
