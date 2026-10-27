# Waydroid shell startup

`cage_xtmapper.sh` starts Waydroid's full UI and then runs XtMapper's
`RemoteServiceShell`. Unless `--no-auto-launch` is passed, that shell process
starts `MainActivity` with the `SHELL_INIT` marker. Shell initialization starts
the mapper with the profile named `Default`, regardless of the profile last
selected in the app. If no `Default` profile exists, XtMapper still requests
`Default`; it does not substitute another saved profile. This shell startup
does not overwrite the last selected profile, so an ordinary app launch
continues to restore the last profile the user selected.

Whether starting a profile also opens its associated app depends on the
**Disable auto profiling** setting. When that setting is enabled, starting an
enabled profile with an associated package launches that package. The setting
defaults to enabled when it has not been saved. When auto profiling is enabled
(the setting is off), XtMapper instead watches the foreground app and switches
to its designated profile; that observer does not launch the app.
