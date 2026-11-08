# Waydroid capture overlay: replacement plan

Status: design and prerequisite gathering; no runtime implementation yet.

Replace cage-xtmapper's nested compositor with an Android-native Wayland client
derived from wayland-getevent. The client runs inside the Android container,
connects to the same host compositor socket that Waydroid receives, and maps a
transparent overlay above the Waydroid window to grab input. XtMapper's
`app_process` owns the client and can hide or show the overlay on demand.
Hiding releases capture and allows ordinary input into Waydroid.

The client and its runtime dependencies ship with the APK; launching the
installed shell server through `waydroid shell` must not require a separate
host client binary, build, or helper installation.

The overlay is a host Wayland surface created from inside Android, separate
from XtMapper's Android cursor/editor overlays. A mapped transparent overlay
still accepts input; hiding means unmapping it, not changing its alpha.

## Waydroid mode and Cage compatibility

Per the [maintainer's direction](https://github.com/Xtr126/XtMapper/pull/237#issuecomment-6717469962),
Xtr126 will discontinue maintaining cage-xtmapper. Preserve XtMapper's existing
Cage integration as an optional legacy backend for community-maintained forks.
Add a documentation link when a maintained fork is identified; do not imply
that one is already available or that upstream Cage maintenance continues.

Detect Waydroid independently of the input backend, using Android
`SystemProperties` rather than a Cage signal. Treat nonempty Waydroid-specific
properties such as `waydroid.tools_version`, `waydroid.xdg_runtime_dir` or
`waydroid.wayland_display` as detection evidence. OTA properties may contain
the literal value `None`; generic properties such as `ro.hardware.egl=mesa`
are not sufficient evidence. Read properties inside Android, without requiring
access to the host's `/var/lib/waydroid/waydroid.prop` file.

Add a persistent **Waydroid mode** UI setting with Auto (default), On and Off.
Auto uses property detection; On enables Waydroid-specific behavior when
detection fails, and Off permits correcting a false positive. Show the detected
and effective mode so an override is understandable. Resolve the setting and
properties before service initialization, including shell startup, and apply
changes through orderly backend teardown/reinitialization. Keep the setting
separate from profile mappings and from native/Cage/evdev backend selection.

Waydroid mode identifies the environment for Linux-specific behavior; it does
not prove a usable Wayland endpoint, socket permissions or compositor support.
Manual On must still validate the endpoint and run the same capability checks;
if properties are missing or invalid, report the configuration problem without
guessing a display path or claiming capture. Detection must work without Cage,
including when no capture backend is connected.

## Context and evidence

Read-only source inspections used these local revisions:

| Project | Revision | Relevant sources |
| --- | --- | --- |
| XtMapper | `e49501f` | `RemoteServiceShell.kt`, `RemoteService.kt`, `InputService.kt` under `app/src/main/java/xtr/keymapper/server/` |
| cage-xtmapper | `03308c0` | `cage_xtmapper.sh`, `BUILDING.md`, `forks/cage/{cage,seat,output}.c` |
| Cage fork gitlink checkout | `a050650` | `seat.c`, `togglekey.h` |
| wayland-getevent | `1fbf7af` | `client.c`, `Makefile`, `README.md` |

The two reference repositories are in `~/Documents`. The Cage checkout had
pre-existing modified submodule gitlinks; this research did not change them.
Public sources: [cage-xtmapper](https://github.com/Xtr126/cage-xtmapper),
[wayland-getevent at the inspected revision](https://github.com/Xtr126/wayland-getevent/tree/1fbf7af0c1ca15c43ad4f65eb63e683d30715a94).

Cage currently creates a nested display, launches Waydroid, and emits synthetic
`/dev/input/wl_*` text records. Its F10 switch changes pointer routing between
XtMapper and Waydroid; it does not hide a capture overlay. Its wrapper already
handles boot waiting, package/APK/ABI lookup, and shell-server launch. Retain
those useful launch checks without stopping/restarting a session just to wrap
Waydroid in Cage. Custom Cage output modes and decorations are outside the
replacement's initial scope; users retain their host compositor's window controls.

wayland-getevent already provides the closer model: a maximized xdg-shell
surface with an ARGB shm buffer, keyboard/pointer listeners, relative motion,
and pointer confinement. Its fresh memfd-backed buffer starts zero-filled,
which provides transparent pixels. Today it runs on the host and pipes text
into the Android shell. It has no app-controlled hide/show channel.

## Prerequisites and observed environment

Read-only host inspection on 2026-11-07 found:

```properties
# Relevant entries from /var/lib/waydroid/waydroid.prop
waydroid.xdg_runtime_dir=/run/xdg
waydroid.wayland_display=wayland-0
```

`/var/lib/waydroid/lxc/waydroid/config_session` maps host
`/run/user/1000/wayland-1` onto container `/run/xdg/wayland-0`.
The installed Waydroid sources confirm that `helpers/images.py` emits the
properties and `helpers/lxc.py` creates the runtime directory/socket mount.
These paths are observations from this installation, not defaults to hardcode.
The session was STOPPED, so the saved mount configuration does not establish
that a live socket or compositor is reachable.

At runtime read Android properties `waydroid.xdg_runtime_dir` and
`waydroid.wayland_display` through `SystemProperties`, and validate their values.
Do not assume Android can read the host `/var/lib/waydroid/waydroid.prop` file.
Resolve a relative display name against the configured runtime directory;
preserve an absolute display path. Pass an explicit endpoint to the native
client rather than inheriting the launching host shell's display accidentally.

Before implementing input capture, gather this evidence in a running session:

| Prerequisite | Required evidence |
| --- | --- |
| Container endpoint | `getprop waydroid.xdg_runtime_dir` and `getprop waydroid.wayland_display`; socket metadata and successful connect as the actual app_process UID/domain |
| Permissions | UID/GID and SELinux context of shell server, mount, socket; record connect errno and relevant denial on failure |
| Host compositor | Name/version, advertised globals and versions, shared-memory ARGB support, pointer and keyboard seat capabilities |
| Overlay behavior | Transparent content, placement over Waydroid, keyboard focus, pointer capture, hide/remap and focus return |
| Geometry | Waydroid full-UI size, overlay logical size, Android injection dimensions, scale and monitor layout |
| Android execution | ABI, Android/API/kernel version, package/APK/native-library paths, successful Binder and input-injection setup |

Use the existing Waydroid socket mount. Do not chmod the runtime directory,
weaken SELinux, or add another display server as a presumed prerequisite.
Root or shell launch privileges alone do not prove socket access. An ordinary
app UID is not assumed to have equivalent access. Inspect
`persist.waydroid.fake_touch` as in the existing shell warning; establish the
same baseline with fake touch disabled before interpreting input failures.

Build prerequisites remain those in [BUILDING.md](../BUILDING.md): JDK 21,
SDK Platform 37, NDK `28.0.13004108`, and ignored local signing properties.
This host has that NDK, SDK directory `android-37.0`, Meson 1.12.1, Ninja 1.13.2,
and host wayland-scanner 1.26.0. Its default `java` reports 25, so verify the
Gradle daemon's JDK 21 selection. Local signing properties exist; their
contents were not read. No Android build was run for this documentation change.

## Native build approach

[wlroots-android-bridge issue #5](https://github.com/Xtr126/wlroots-android-bridge/issues/5)
reports Linux-hosted Meson/NDK r28 cross-compilation, including libffi 3.4.6,
expat 2.6.4, Wayland 1.24.0 and wayland-protocols 1.41. It identifies host
scanner selection and cross pkg-config paths as important; its `sys_root`
setting interfered with protocol XML discovery. The issue concerns a server
stack, so it is evidence for a build strategy, not a verified XtMapper client
recipe. Its wlroots-specific librt/shm changes should not be copied blindly.

Proposed client-only dependency graph:

```text
NDK Clang + libffi + expat -> libwayland-client -> libxtmapper_wayland.so
host wayland-scanner + pinned protocol XMLs -> generated client bindings --^
```

1. Pin sources/checksums and licenses. Start a client-only cross-build with
   the issue's Wayland/ffi/expat versions, checking which dependencies the
   selected client target actually needs. Build host tools separately.
2. Use one explicit NDK target/API and isolated pkg-config/sysroot search
   paths per Android ABI. Start with the actual Waydroid ABI (commonly x86_64),
   then arm64-v8a; the issue's arm64 result does not prove x86_64 works.
3. Generate extension client headers/private C from xdg-shell,
   pointer-constraints and relative-pointer XML with a compatible host scanner.
   Core Wayland bindings come from the Wayland build. Treat protocol XMLs as
   build inputs, not Android executables. Add activation bindings only if used.
4. Disable server/compositor, documentation, examples and unrelated features
   where the pinned build supports it. Do not pull in wlroots, DRM, pixman,
   libinput or EGL for this shm input-overlay client.
5. Prefer position-independent static dependencies behind one JNI library,
   exported through the existing `app/Android.mk` ndk-build integration. Make
   third-party outputs deterministic inputs to that build; document how to
   regenerate them. Verify licenses before copying reference source.
6. Inspect target ELF architecture, undefined symbols, SONAME/DT_NEEDED and
   API availability with NDK tools. Verify page-size compatibility and APK
   extraction/loading via the installed native-library path in app_process.

For new physical-key profiles, use Wayland's evdev key codes and XtMapper's
Linux key-name table (`app/src/main/cpp/linux_key_table.h`). This avoids
libxkbcommon and an XKB data-tree deployment for the initial client, but is
not a drop-in interpretation of existing Cage profiles. Cage derives names
from XKB symbols (including modifier-sensitive names and special aliases),
whereas `KeyEventHandler` compares event names directly with saved codes.
On AZERTY, the physical evdev `KEY_Q` position can produce Cage's `KEY_A`;
reinterpreting a saved `KEY_A` as evdev would move its action to another key.

Before native-backend activation, introduce versioned profile key semantics
and provenance: legacy Cage symbol names versus physical evdev names. Preserve
unversioned profiles unchanged; their origin cannot be inferred from strings
such as `KEY_A`. Require the user to identify their semantics when switching
backends. Physical profiles can use evdev directly. For legacy Cage or unknown
profiles, block activation on the native backend until an explicit migration
is completed, while leaving the original usable with the retained Cage path.

Provide a migration UI that copies the profile, asks the user to press each
intended physical key, and records its evdev name in the versioned copy. Cover
ordinary keys, D-pads, swipe/shift keys, macro and camera triggers, and relevant
global shortcuts; preserve all other mappings and metadata. Show changes for
confirmation, support cancellation without modifying the original, and flag
unresolved bindings rather than guessing a US-layout substitution. Translate
the selected semantics before `KeyEventHandler` and its shortcut handlers;
never feed legacy names directly into the physical-key path. Retaining
automatic symbol compatibility instead would require a separately designed
XKB/keymap adapter and revisiting the dependency decision.

Validate names against `KeyEventHandler` and numeric mouse codes against
`InputEventCodes`. Keep modifiers, simultaneous keys, release tracking and
repeat policy explicit; remember the translated identity on key-down so a
modifier/layout change before key-up cannot leave a mapped action pressed.

Use memfd + ftruncate + wl_shm for transparent buffers, checking kernel/API
availability, sizes and errors. Specify an Android-compatible shared-memory FD
fallback if the probe requires it. Keep buffers alive until compositor release;
bound allocation on configure/resize. No GPU or Android Surface is needed for
the proposed transparent shm surface; verify that assumption with the probe.

## Ownership, control and capture state

Preferred architecture: JNI library in the privileged shell app_process,
owned by a `WaylandInputBackend` with one native dispatch thread. The existing
authenticated app-to-server Binder route requests capture changes; an internal
command queue plus wakeup FD transfers them to the dispatch thread. Do not
expose a world-accessible control socket or use stdout for control.

Proposed internal operations are `connect(endpoint)`,
`setCaptureVisible(visible, generation)`, and `close()`. Results report the
generation, connection, mapping, focus and constraint state separately.
All Wayland object creation/destruction happens on the dispatch thread;
Binder/main-handler calls enqueue work. Connection and dispatch must never
block Android's main looper. Use a poll/prepare-read dispatch loop that also
services commands and shutdown, with cancellation, bounded queues and joining.

| State | Surface/input behavior |
| --- | --- |
| Disconnected | No capture; report connection error and allow an explicit retry |
| Hidden | Connection alive, surface unmapped, constraints released, held mapped input canceled |
| Showing | Create/remap transparent surface, request geometry, await configure/ack and focus |
| Capturing | Mapped with appropriate focus; route accepted events into XtMapper |
| Suspended | Focus lost or constraint unavailable; release mapped state and report why |

Start hidden until an enabled profile and input service are ready. Starting or
resuming mapping requests show; pausing for the editor or stopping/disabling a
profile requests hide. Hide must work even during a pending configure or an
unsuccessful show. Track desired state and generations so late events cannot
reactivate an overlay after hide or profile replacement.

Hide releases locks/confinement and pressed keys/buttons/touches, then commits
a null buffer to unmap. Show re-establishes toplevel state and follows the
initial configure/ack/buffer-commit sequence. Destroying/recreating surface and
role objects on show is an acceptable simpler first implementation. Do not use
`set_minimized` as a reversible visibility API. Keep connection and capability
state while hidden, and destroy remaining objects/FDs on close. These sequences
follow the upstream [xdg-shell protocol](https://gitlab.freedesktop.org/wayland/wayland-protocols/-/blob/main/stable/xdg-shell/xdg-shell.xml).

A hidden client cannot receive a global resume hotkey through ordinary
`wl_keyboard`. Unhide must be callable from XtMapper's UI/notification over
Binder. If an F10/global toggle is wanted while hidden, evaluate compositor
shortcuts/portal support separately; do not promise Cage's shortcut behavior.
Hide must release capture even if Android callbacks fail or the app Binder dies.

## Overlay placement and compositor feasibility gate

The transparent pixels must leave the underlying Waydroid game visible while
the mapped overlay's input region accepts input. Avoid marking it opaque or
using an empty input region. Transparent appearance does not itself confer
stacking, keyboard focus or a global grab.

Start with xdg-shell as in wayland-getevent, and test a stable app_id with
compositor window rules to float/place it over Waydroid where needed. Matching
maximized sizes alone is insufficient on tiling desktops. Standard xdg-shell
does not guarantee arbitrary positioning, always-on-top behavior or restored
focus. Activation requests may depend on a valid compositor token.

If placement/focus cannot be reliable enough, evaluate an optional layer-shell
surface on supporting compositors, with suitable keyboard interactivity and
output/region selection. That is a separate backend with a declared support
matrix, not a portable assumption. This proposal initially targets full-UI
Waydroid; multi-window Android apps require a separate alignment policy.

Enumerate globals and bind supported versions safely. Require compositor/shm,
xdg-shell (for that backend) and relevant seat capabilities. Require relative
pointer and pointer constraints for unlimited aim mode; report unavailable
capabilities rather than dereferencing missing globals. Confirm actual
locked/confined notifications before claiming capture. Test whether aim should
lock the pointer instead of confining it at window edges. On focus/seat/global
loss, release held input; retain desired visibility without reporting capture
until focus and constraints recover.

Gate: an NDK probe launched under the real shell-server identity must connect,
show a transparent capture surface, receive input, hide, return input to
Waydroid, and show again. Include focus restoration and correct Android game
touch injection while the host focuses the overlay. If this needs compositor
rules, document them; if a compositor cannot support it, offer the retained
legacy Cage path when a user supplies a compatible community fork, or report
the unsupported configuration. Do not promise a maintained upstream fallback.

## XtMapper integration and event correctness

Fix initialization first: `RemoteService` calls `init()` in its constructor,
but `RemoteServiceShell` assigns `isWaylandClient` afterward. Backend selection
must be immutable constructor configuration before discovery starts. Resolve
Waydroid detection and its persisted override independently before selecting
that configuration; Cage input must not be required to identify Waydroid. Keep
legacy stdin mode separate during migration; both must avoid unintended evdev
discovery. Native events/control replace a host pipe for the new backend.

Normalize native events into typed keyboard, button, absolute, relative, wheel
and focus events. A compatibility adapter can emit existing `wl_*` records for
listeners, but parser layout must be tested: `onWaylandMouseEvent()` expects
the leading space after the colon. Keep diagnostics out of input streams.

Correct the reference client's hazards during adaptation: pass the real client
state to callbacks (the relative listener currently receives `&state`), handle
missing/removed globals, support middle and other buttons explicitly, avoid
axis-zero division, define wheel/high-resolution behavior, validate dimensions
and release resources. Accumulate fractional relative deltas without drift.
Avoid duplicate absolute/relative movement; transform surface-local logical
coordinates to Android display coordinates, including scale, resize and
letterboxing. Preserve evdev code identity and suppress synthetic repeat unless
the mapper requires it. Clear pressed state on leave/hide/disconnect so keys
and injected touches cannot stick.

`InputService.pauseResumeKeymap()` currently toggles `stopEvents` without a
Wayland hide/show command. `RemoteService.stopServer()` omits much of its
cleanup for Wayland mode. Integrate capture release into those paths, profile
replacement, callback death and process shutdown. Serialize lifecycle decisions
on the main handler; define event-thread synchronization and discard stale
service-generation events. Avoid creating evdev/uinput cursor resources unless
the selected pointer mode actually needs them. Preserve shell startup's
`Default` selection and other profiles' mappings/metadata.

## Implementation sequence and validation

1. **Mode detection and prerequisite probe:** define the property detector,
   persistent UI override and backend-independent environment state; capture
   live endpoint permissions, globals and compositor behavior; cross-build/load a minimal JNI client for the target
   ABI. Save reproducible commands and results with the actual UID/domain.
2. **Overlay lifecycle:** implement transparent map/unmap, command wakeups,
   focus/constraint status and teardown independently of mapper event injection.
   Demonstrate repeated show/hide and the hidden-state Binder resume path.
3. **Input/backend integration:** immutable backend startup, event translation,
   capture controls and service/profile/editor lifecycle. Retain evdev and the
   optional community-fork Cage integration; label legacy stdin and native
   backend distinctly without promising continued upstream Cage maintenance.
4. **Packaging and support:** pin dependencies, integrate ndk-build/APK loading,
   add build instructions and a compositor/ABI support matrix. Supply a simple
   launch path through `waydroid shell` using Waydroid's existing socket and
   installed APK libraries, with no separately installed host client or helper.
   Document upstream Cage discontinuation and link a community fork if available.

Add focused JVM tests for property detection with present, missing, empty and
partial Waydroid properties, rejection of generic Mesa-only evidence, and
Auto/On/Off persistence and precedence before app and shell initialization.
Verify that manual On with an invalid endpoint reports failure without capture,
and that mode changes preserve profiles and release the previous backend.
Add focused JVM tests for backend selection before init, state/generation
transitions, event normalization and cleanup. Add native tests for delta
accumulation, buffer dimension checks and resource teardown where practical.
Require profile migration tests for legacy, physical and unknown provenance,
cancel/retry, unresolved bindings and preservation of original profile data.
Use a non-US AZERTY fixture where Cage emits `KEY_A` for evdev `KEY_Q`:
verify activation is blocked before migration, rebinding preserves the intended
physical action afterward, and a US-layout fixture retains its expected action.
Cover every binding category and shortcut, plus modifier/layout changes between
press and release with no stuck input. Repeat the AZERTY scenario on a device
with an existing Cage profile and the native backend before claiming compatibility.
Run Kotlin compilation, native-inclusive `assembleDebug`, and relevant JVM
tests per [BUILDING.md](../BUILDING.md). Kotlin compilation alone is insufficient.

Device acceptance must verify detection without Cage, manual enablement when
detection fails, explicit disablement and override persistence after restart.
Install the APK and launch through `waydroid shell` on a host without Cage or
wayland-getevent installed; verify packaged library loading and capture.
Exercise the retained legacy Cage path separately with a compatible fork when
available, recording its revision rather than assuming upstream support.

Device acceptance must cover mouse buttons/wheel/aim, simultaneous keyboard
keys, pointer/keyboard leave, seat removal, compositor/socket restart, missing
protocols, denied socket access, scale/resize/multiple monitors, rapid show/hide,
editor pause/resume, disabled Default, profile switching, Binder death and
shutdown with no stuck input or leaked FDs/threads. Check the host cursor and
Android cursor modes for duplicate movement. Capture both app and server logs
using [service-debugging.md](service-debugging.md), logging transitions and
capabilities rather than high-frequency events.

Current verification is source/configuration research and documentation review
only. The NDK cross-build, live socket access, compositor focus/stacking,
map/unmap behavior and Android input injection remain unverified. An ADB device
was listed, but it was not identified as this stopped Waydroid session and was
not used to claim runtime validation.
