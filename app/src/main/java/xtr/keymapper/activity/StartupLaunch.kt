package xtr.keymapper.activity

import xtr.keymapper.keymap.KeymapProfiles

internal object StartupLaunch {

    /**
     * Profile to auto-start for an authenticated shell launch, or null for manual startup.
     * Authenticity comes from the launch identity, never from Intent extras.
     */
    fun shellProfile(authenticatedShellLaunch: Boolean, availableProfiles: Set<String>): String? =
        KeymapProfiles.DEFAULT_PROFILE_NAME.takeIf {
            authenticatedShellLaunch && it in availableProfiles
        }

    fun autoStartProfile(authenticatedShellLaunch: Boolean, availableProfiles: Set<String>): String? =
        shellProfile(authenticatedShellLaunch, availableProfiles)
}
