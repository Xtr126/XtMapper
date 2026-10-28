# Waydroid shell startup

`cage_xtmapper.sh` starts Waydroid's full UI and then runs XtMapper's
`RemoteServiceShell`. Unless `--no-auto-launch` is passed, that shell process
requests `MainActivity` with the legacy `SHELL_INIT` marker.

The marker is an Intent extra, not proof of the caller's identity. XtMapper only
treats a launch as trusted shell initialization when Android exposes the actual
launching UID and it matches the shell UID. On systems where Android does not
expose that identity, `data=shell` is not enough to trigger automatic mapper
startup or a persistent Shizuku-to-Root settings change; launch XtMapper normally
and start the mapper from its UI instead. This conservative fallback prevents an
external app from spoofing the marker.

For a trusted shell initialization, XtMapper requests the profile named
`Default`, regardless of the profile last selected in the app. If no `Default`
profile exists, XtMapper still requests `Default`; it does not substitute
another saved profile. A trusted shell startup does not overwrite the last
selected profile, so an ordinary app launch continues to restore the last
profile the user selected.

Whether starting a profile also opens its associated app depends on the
**Disable auto profiling** setting. When that setting is enabled, starting an
enabled profile with an associated package launches that package. The setting
defaults to enabled when it has not been saved. When auto profiling is enabled
(the setting is off), XtMapper instead watches the foreground app and switches
to its designated profile; that observer does not launch the app.

For logcat capture commands and startup/input checkpoints, see
[Debugging startup and mouse input](input-debugging.md).
