package xtr.keymapper.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
