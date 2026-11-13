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

On Android 13 and older, Android does not expose the launching UID for this
activity launch. MainActivity therefore checks a challenge callback from the
supplied service Binder and accepts it only when Binder reports that the callback
came from UID 2000 (the shell process). An arbitrary Binder or the caller-controlled
`data=shell` extra is not accepted as proof of identity.

A verified Binder lets the user start mapping through the shell service without
granting root access to the app. These older versions still use manual startup:
they do not auto-start the `Default` profile because Android does not expose the
launcher identity. The saved Shizuku-to-Root mode and last-selected profile remain
unchanged. The `data=shell` marker only suppresses replay of a crash payload on
the shell request. The same Binder challenge is used on newer Android versions
when the launching identity is unavailable.

Whether starting a profile also opens its associated app depends on the
**Disable auto profiling** setting. When that setting is enabled, starting an
enabled profile with an associated package launches that package. The setting
defaults to enabled when it has not been saved. When auto profiling is enabled
(the setting is off), XtMapper instead watches the foreground app and switches
to its designated profile; that observer does not launch the app.

For logcat capture commands and startup/input checkpoints, see
[Debugging startup and mouse input](input-debugging.md).
