# Waydroid Wayland feasibility probe

The debug APK has a standalone `app_process` entry point for the first gate
in [PR #237's overlay plan](waydroid-wayland-client-plan.md). It reads the
container's `waydroid.xdg_runtime_dir` and `waydroid.wayland_display` Android
properties, connects to the existing compositor socket, and tries a transparent
maximized `xdg_toplevel` with app ID `org.xtmapper.WaylandProbe`. The probe
shows for 10 seconds, hides for 5 seconds, then shows again for 10 seconds.

## Launch on Waydroid

With Waydroid running, install the current debug build and bring Waydroid's
full UI to the foreground. Use recognizable Android content and leave any
XtMapper mapping session stopped during the test.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Capture both app and server logs as in [service debugging](service-debugging.md).
For the standalone probe, add its tag to the logcat command or capture it
and Android runtime errors in a separate terminal:

```sh
adb logcat -v threadtime 'xtmapper-wayland-probe:V' 'AndroidRuntime:E' '*:S'
```

Enter the Android shell through `sudo waydroid shell` for the intended
Waydroid launch identity or `adb shell` for the UID 2000 comparison. Run the
following commands inside that shell. Resolve the paths after each APK update.

```sh
id
id -Z
getenforce
getprop ro.build.version.release
getprop ro.product.cpu.abi
runtime=$(getprop waydroid.xdg_runtime_dir)
display=$(getprop waydroid.wayland_display)
printf 'runtime=%s display=%s\n' "$runtime" "$display"
case "$display" in
  /*) endpoint=$display ;;
  *) endpoint=$runtime/$display ;;
esac
ls -ldZ "$runtime" "$endpoint"
grep -F "$endpoint" /proc/self/mountinfo

pkg=xtr.keymapper.debug
apk=$(pm path "$pkg" | sed -n '1s/^package://p')
package_info=$(dumpsys package "$pkg")
lib_base=$(printf '%s\n' "$package_info" | sed -n 's/^    legacyNativeLibraryDir=//p')
abi=$(printf '%s\n' "$package_info" | sed -n 's/^    primaryCpuAbi=//p')
lib="$lib_base/$abi"
if [ -f "$apk" ] && [ -d "$lib" ]; then
  /system/bin/app_process "-Djava.class.path=$apk" "-Djava.library.path=$lib" \
    / xtr.keymapper.wayland.WaylandProbe
fi
```

If the fresh Waydroid shell lacks `BOOTCLASSPATH`, use the framework/APEX
classpath exported by the running Android image, for example the value from
`adb shell 'printf "%s\n" "$BOOTCLASSPATH"'` on the same container. Record any
launcher or library loading failure separately from the socket result. If
`connect` fails, record its errno, the identity, the mount and socket modes,
and any SELinux denials. Keep the socket permissions and SELinux policy as
provided by the session.

## Evaluate the gate

Record the host compositor and version, whether compositor rules were needed,
the monitor arrangement and scale, and the Waydroid full-UI geometry. Repeat
the test on each compositor configuration the overlay would need to support.

| Check | Evidence |
| --- | --- |
| Android and socket | APK library loads in `app_process` under the intended UID/domain. A Unix socket connection and successful protocol exchange are recorded separately. |
| Capabilities | Registry includes the compositor, shared memory, xdg-shell and a seat with keyboard and pointer capabilities. |
| Placement | The Waydroid content stays visible through the transparent surface and the probe covers the intended window bounds. Check host compositor window geometry and stacking. A matching configure size alone leaves placement unverified. |
| Focus and input | Move the pointer over the expected region and press a test key. Record pointer and keyboard focus separately. Distinguish automatic focus from focus obtained by clicking or using the compositor's window controls. |
| Hide and show again | During the hidden interval, observe whether ordinary pointer and keyboard input return to Waydroid without intervention. Verify placement and focus again on the second show. |

Run first with the compositor's normal policy, then with a targeted rule for
`org.xtmapper.WaylandProbe` if needed. Record the exact rule and whether the
same behavior repeats. Standard xdg-shell leaves placement, stacking and
focus policy to the compositor. Android touch injection while the overlay has
focus remains a separate device acceptance check before enabling a backend.

The debug probe implements only the Wayland wire messages it needs, using
version 1 of the bound interfaces. It links against the Android NDK runtime
and liblog and packages no server library. The full backend's dependency
choice remains open. The probe reports the initial configured size and holds
that buffer size for each visible interval. Use a fixed monitor and window
configuration during each run.

The exit status distinguishes invalid configuration (`2`), socket connection
failure (`3`), capability exchange failure (`4`), surface or dispatch failure
(`5`), and missing focus on either show (`6`). Status `0` means each surface
committed a buffer and received keyboard and pointer enter events. Use the
manual checks above to assess stacking, input delivery and focus restoration.

## Observed results

The host control connected as UID 1000 on KWin 6.8.3. On both shows the
compositor configured a 1920 × 1058 surface, delivered keyboard and pointer
enter events, and delivered a pointer button event. Destroying each surface
produced keyboard and pointer leave events. This establishes compositor-side
surface creation and focus under the desktop user. Waydroid placement and
focus restoration require a run from inside the container.

On Waydroid 2.0.0 (Android 13/API 33, x86_64), the debug APK's JNI
library loaded in `app_process` started by ADB shell (UID/GID 2000,
SELinux disabled). Android reported `/run/xdg` and `wayland-0`. The
container mount points to the existing KWin socket. The socket was mode
`0755`, UID/GID `1000:985`, and `connect()` returned `EACCES` (errno
13). The standalone probe returned `3`; an independent `nc -U` check
under the same UID also returned permission denied.

The intended launch identity was verified with
`pkexec /usr/bin/waydroid shell`. The JNI probe ran as UID/GID 0,
connected to `/run/xdg/wayland-0`, and completed the protocol exchange.
Both runs returned `0`. On the repeat run, both surfaces received
keyboard and pointer enter immediately after committing. The first
received a key and a pointer button, and the second received a pointer
button. The first run recorded key delivery on both shows.

On KWin 6.8.3 with one active 1920 × 1080 output at scale 1, KWin
reported both probe surfaces above the Waydroid full-UI window with
matching frame bounds at (0, 22), 1920 × 1058. The probe used default
placement and focus policy without a probe-specific window rule.
The geometry and stacking check used KWin window data. Visual
transparency still needs a direct check.

KWin activated Konsole after each hide on the repeat run. The
`pkexec` authentication prompt had taken focus before the probe
started, so this run did not verify whether focus returns when
Waydroid is active immediately before mapping. Connection,
focus acquisition, and placement are viable on this configuration.
Before enabling an overlay backend, verify the focus handoff from an
initially active Waydroid window and Android touch injection while the
probe is focused. Keep the existing UID 2000 Binder authentication
when designing the eventual service handoff for a root-launched
capture client.

## Build and test results

- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest`
  built both APKs and passed 423 JVM tests. The probe library was packaged for
  all four app ABIs. The 64-bit ELF load segments were aligned to 16 KiB.
- The focused `WaylandProbeTest` instrumentation suite passed all four tests
  on the x86_64 Waydroid device.
- The touchpad and native diagnostics CTest suites passed all eight checks.
- `./gradlew :app:lintDebug` reported 13 errors in existing files on the
  rebased branch. These cover the deprecated quick-settings tile API and
  missing translations.
