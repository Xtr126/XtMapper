# Waydroid shell startup

`cage_xtmapper.sh` starts Waydroid's full UI and then runs XtMapper's
`RemoteServiceShell`. Unless `--no-auto-launch` is passed, that shell process
requests `MainActivity` with the legacy `SHELL_INIT` marker.

The marker is an Intent extra, not proof of the caller's identity. Android does
not expose the launching UID for an `am start` launch that does not share
identity, so XtMapper cannot authenticate the shell request and has no trusted
shell-initialization path: `data=shell` never triggers automatic mapper
startup, never selects the `Default` profile, and never resets the saved
Shizuku-to-Root mode. Launch XtMapper normally and start the mapper from its
UI; startup restores the profile last selected in the app. The marker's only
remaining effect is to suppress replay of a crash payload on the shell request.

Whether starting a profile also opens its associated app depends on the
**Disable auto profiling** setting. When that setting is enabled, starting an
enabled profile with an associated package launches that package. The setting
defaults to enabled when it has not been saved. When auto profiling is enabled
(the setting is off), XtMapper instead watches the foreground app and switches
to its designated profile; that observer does not launch the app.

For logcat capture commands and startup/input checkpoints, see
[Debugging startup and mouse input](input-debugging.md).
