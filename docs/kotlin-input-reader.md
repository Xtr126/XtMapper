# Kotlin input monitor (draft)

This change addresses the system-getevent dependency suspected in issue #216.
The OxygenOS Android 16 report has not been reproduced locally; replacing the
reader is not yet proof that the reported mapping failure is resolved.

`EvdevReader` enumerates `/dev/input/event*` and uses Android's Java `Os.open`,
`Os.poll`, and `Os.read` APIs. It does not launch `getevent`, preload a helper,
or add JNI/C/C++ code. Existing native mouse and exclusive keyboard-grab paths
remain in use. The generated NDK constants/labels from PR #239 provide labels
independently of the device's system utilities.

One worker owns all nonblocking descriptors, polls for at most 250 ms, and
rescans every second for hotplug or retry. Closing requests termination; the
worker closes its descriptors in `finally`. Device open/read/disconnect failures
are logged under `xtmapper-evdev`, without logging every input event. A successful
open alone does not establish event delivery. Access still requires the existing
privileged server identity and device permissions.

The decoder handles native endianness, 16-byte 32-bit and 24-byte 64-bit
`input_event` records, partial reads, batches, and signed relative values. It
formats the existing `device: EV_KEY KEY_W DOWN` protocol for the mapping/editor
listeners. Mouse discovery is still queued to the main handler. Exclusive grabs
continue to route through the existing native readers. Optional Cage input still
uses its stdin protocol; the backend is selected before reader construction.

## Local checks

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

Tests cover ABI/endianness, fragmented/batched records, repeat/release and unknown
labels, signed mouse values, device removal, and descriptor ownership on shutdown.
They do not exercise Android syscalls, Binder delivery, or actual evdev devices.

## Device validation before leaving draft

- Reproduce #216 on OxygenOS Android 16: create/save/close an editor mapping,
  exercise WASD, tap, shooting-mode toggle, pause/resume, and editor shortcuts.
  Capture both app/server logs plus `xtmapper-evdev`.
- Exercise shell, root, and Shizuku access, including denied device opens and
  retry after access is restored. Check actual key delivery after open.
- Test both 32-bit and 64-bit server processes where available; test keyboard
  repeat/release, mouse movement/wheel, and multiple input devices together.
- Unplug/replug devices, including reuse of an event path. Check discovery and
  reconnect; verify no descriptor growth after repeated reconnects.
- Stop/restart mapping, reopen the editor, stop the service, and exercise
  exclusive keyboard and mouse grabbing. Check that the monitoring reader does
  not double-deliver events or interfere with native grab teardown.
- Verify optional Cage stdin and mouse handling, including startup mode selection.

Kernel `SYN_DROPPED` recovery and releasing held mappings after device loss are
not implemented by this monitor; device tests should include these conditions
before deciding whether further recovery work is required.
