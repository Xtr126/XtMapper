# Waydroid shell startup

`cage_xtmapper.sh` starts Waydroid's full UI and then runs XtMapper's
`RemoteServiceShell`. Unless `--no-auto-launch` is passed, that shell process
requests `MainActivity` with the legacy `SHELL_INIT` marker.

The marker is an Intent extra, not proof of the caller's identity. On Android 14
(API 34) and newer the shell process attaches an `ActivityOptions` bundle with
`setShareIdentityEnabled(true)` to its `startActivityAsUser` request, so
`MainActivity` can read `getLaunchedFromUid()` and accept the launch as
authenticated only when it equals the shell UID. An authenticated launch starts
the `Default` profile automatically when that profile exists. It does not
persist a new last-selected profile, so the next manual launch still restores
the user's choice, and it never changes the saved Shizuku-to-Root mode.

Auto-start also stays silent: it never opens permission dialogs, never opens the
overlay settings screen, and never exits the app. If root, Shizuku, or overlay
access is missing, the launch simply falls back to the normal manual flow.

On Android 13 and older, or whenever the launch identity is unavailable,
XtMapper keeps the manual behavior: `data=shell` never triggers automatic mapper
startup, never selects the `Default` profile, and never resets the saved
Shizuku-to-Root mode. Launch XtMapper normally and start the mapper from its UI;
startup restores the profile last selected in the app. The marker's only
remaining effect is to suppress replay of a crash payload on the shell request.

Whether starting a profile also opens its associated app depends on the
**Disable auto profiling** setting. When that setting is enabled, starting an
enabled profile with an associated package launches that package. The setting
defaults to enabled when it has not been saved. When auto profiling is enabled
(the setting is off), XtMapper instead watches the foreground app and switches
to its designated profile; that observer does not launch the app.

For logcat capture commands and startup/input checkpoints, see
[Debugging startup and mouse input](input-debugging.md).
