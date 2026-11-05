# Debugging startup and mouse input

Use this workflow when XtMapper stays on “connecting to server,” detects a
mouse without receiving input in Java, crashes during shell startup or general service-related issues.
The commands below use the debug package, `xtr.keymapper.debug`.

## Capture both app and server logs

Build and install the version containing the diagnostics. Open XtMapper once
so it regenerates `xtMapper.sh` with the installed APK and native library paths.
After reinstalling or updating, regenerate the script before running it again.

Start this capture in a separate host terminal **before** starting the server:

```bash
adb logcat -v threadtime 'xtmapper-startup:V' 'xtmapper-server:V' 'xtmapper-mouse-lifecycle:V' 'XtMapperMouse:V' 'TouchPointer:V' 'startServer:V' 'AndroidRuntime:E' '*:S' | tee xtmapper-debug.log
```

Then start the shell server in another terminal:

```bash
adb shell sh /sdcard/Android/data/xtr.keymapper.debug/files/xtMapper.sh
```

Enable the profile, move the mouse, and reproduce the failure. Stop capture
with Ctrl+C after the relevant events. Record the device/Android version,
profile name, pointer mode, activation method (shell, root, or Shizuku), and
whether the capture includes a fresh server launch.

The script's built-in logcat uses `--pid` for the shell server. It misses app
process messages such as Binder receipt and profile activation. Use the
separate capture above to trace both processes. Keep the timestamps, PIDs,
and thread IDs: `threadtime` makes cross-process ordering visible. Quote
`'*:S'` as shown so the host shell does not expand the wildcard.

| Tag | What it reveals |
| --- | --- |
| `xtmapper-startup` | MainActivity shell marker/Binder receipt, service selection, profile disabled state, and calls to `startServer()` |
| `xtmapper-server` | Shell launch result, getevent discovery queued to the main handler, service construction, JNI return values, and shutdown |
| `xtmapper-mouse-lifecycle` | Discovery, service connect/disconnect, deferred or skipped opens, retries, and success |
| `XtMapperMouse` | Native device opening, file descriptors, `EVIOCGRAB`, input reads, and JNI callback diagnostics |
| `TouchPointer`, `startServer` | App-side connection warnings and exceptions |
| `AndroidRuntime` | Fatal Java/Kotlin exceptions, including failures outside the normal server logs |

For a crash with missing context, also save the unfiltered buffers soon after
reproducing it:

```bash
adb logcat -b main -b system -b crash -d -v threadtime > xtmapper-full.log
```

## Follow startup in stages

1. **Shell process launches the app.** Look for `shell startup` with
   `autoLaunch=true`, delivery of the service Binder, and the activity launch
   result. A successful launch result alone does not prove the app received
   the Binder.
2. **App receives the shell Binder.** Expect `MainActivity.onCreate` with
   `shellMarker=true binderPresent=true`, then `setShellServiceBinder` with
   `alive=true`. If the helper instead falls back to root/Shizuku binding,
   inspect whether the Binder was absent, dead, or replaced.
3. **Profile activation proceeds.** Check `connectRemoteService`, its callback,
   and `disabled`. A live Binder proves connection, not profile activation.
   `profile disabled; startServer skipped` explains why no input service is
   created. Enable the profile through its card menu. Shell initialization
   selects `Default`; see [shell startup behavior](waydroid-shell-startup.md).
4. **Server receives startup.** Expect `startServer: requested`, main-handler
   execution, `InputService` construction, mouse lock setup, and connection to
   the mouse lifecycle state. If the app logs a call but the server never logs
   receipt, inspect app-side exceptions and Binder failures.
5. **Device discovery joins service startup.** Discovery may precede service
   creation. `waiting for input service` is normal until `connect` follows.
   `waiting for device discovery` means the service arrived first; move the
   mouse and check for discovery. Neither message alone identifies a bug.
6. **Device opens and events reach Java.** Follow the lifecycle open attempt,
   the JNI call and returned file descriptor, then native grab/read/callback
   diagnostics. A nonnegative descriptor means `open()` succeeded; it does
   not by itself prove grabbing, reads, or callbacks succeeded.

Repeated relative events in the same waiting/already-open state are suppressed
in lifecycle logging. Each failed open is still reported and can retry on
subsequent discovery. Missing repeated discovery logs do not prove input stopped.

## Separate device detection from event delivery

If needed, inspect kernel input devices independently:

```bash
adb shell getevent -lp
adb shell getevent -lt /dev/input/event10
```

Use the event path reported on the device; `event10` is only an example and can
change. Stop this probe after moving the mouse. Device access depends on shell
permissions. Successful `getevent` output shows kernel input exists, but does
not prove XtMapper opened the device or delivered a JNI callback. A successful
exclusive grab can also prevent a separate reader from seeing events.

Use the boundary where logs stop to narrow the investigation:

- Discovery but no `connect`: inspect service startup and profile activation.
- JNI open returns a negative descriptor: inspect the native error/errno.
- Open succeeds but grab fails: inspect `EVIOCGRAB` diagnostics.
- Reads occur but Java sees nothing: inspect JNI callback diagnostics and exceptions.

## Findings from the shell-startup investigation

A capture showed a healthy Binder handoff but `Default disabled=true`.
`startServer()` was intentionally skipped, so discovery waited indefinitely for
an input service. The old “connecting to server” toast was misleading. The UI
now exposes enable/disable controls and explains disabled activation with a toast.
This case demonstrates why device-discovery logs alone were insufficient.

A separate, intermittent crash began with `Unknown pid=… uid=2000` in
`IWindowManager.openSession` while adding the server cursor overlay. That
`IllegalStateException` was caught, then the cursor fallback called the app's
`enablePointer()` and received a `NullPointerException` over Binder. This trace
establishes the failing sequence, but does not identify the app-side null value.
Capture both processes and full crash context if it recurs; do not treat it as
proof that mouse discovery or the lifecycle fix failed.

## Local regression checks

```bash
./gradlew :app:testDebugUnitTest --tests xtr.keymapper.server.MouseDeviceConnectionTest --tests xtr.keymapper.keymap.KeymapProfilesTest --console=plain
```

The lifecycle tests cover discovery before/after connection, retry after open
failure, and reconnection. Profile tests cover enabling `Default` without an app
association and preserving profile contents/isolation. These checks do not
validate device permissions, WindowManager behavior, grabbing, or JNI event
delivery; reproduce on the affected Android device as well.
