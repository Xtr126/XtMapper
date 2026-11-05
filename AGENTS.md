# Repository guidance

## Project layout

XtMapper is an Android keyboard/mouse mapper with Kotlin application code,
AIDL services, and native input handling.

- `app/src/main/java/xtr/keymapper/`: application and mapping logic.
- `app/src/main/java/xtr/keymapper/ui/GameKeyMapper.kt`: Compose UI; its Kotlin
  package is `xtr.keymapper`, despite the `ui` directory.
- `app/src/main/java/xtr/keymapper/server/`: privileged service, shell entry
  point, and input-service lifecycle.
- `app/src/main/aidl/`: Binder interfaces between the app and server processes.
- `app/src/main/cpp/`: native input code, built through `app/Android.mk`.
- `app/src/test/`: local JVM regression tests.
- `app/hidden-api/`: Android hidden API stubs.

## Build and verification

Read [BUILDING.md](BUILDING.md) for prerequisites and signing setup. Use the
Gradle wrapper from the repository root. Even debug builds currently require
an ignored local `keystore.properties`; do not commit signing credentials.

```sh
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:assembleDebug --console=plain
./gradlew :app:testDebugUnitTest --console=plain
```

Choose verification appropriate to the change. Kotlin compilation does not
validate native code or produce an installable APK. For focused JVM checks:

```sh
./gradlew :app:testDebugUnitTest --tests xtr.keymapper.server.MouseDeviceConnectionTest --tests xtr.keymapper.keymap.KeymapProfilesTest --console=plain
```

Input permissions, Binder behavior, overlays, device grabbing, and JNI event
delivery require Android device testing. State clearly which checks ran and
which device behavior remains unverified.

## Implementation conventions

- Preserve unrelated working-tree changes and user edits. Make focused changes
  and separate commits by logical purpose when committing is requested.
- Keep UI strings in `app/src/main/res/values/strings.xml` and use resources
  from Compose and service toasts.
- Preserve stored profile mappings and metadata when changing profile flags.
  Enabling/disabling one profile must not silently change other profiles.
- Shell initialization selects `Default`, independently of the last selected
  profile. See [shell startup behavior](docs/waydroid-shell-startup.md).
- Keep mouse discovery/connection state on the main handler; getevent output
  arrives on a background thread. Do not equate discovery with a successful
  open, or a successful open with successful event delivery.
- Keep native file-descriptor/thread ownership and service teardown in mind
  when changing input lifecycle code.

## Debugging references

Read [Debugging startup and mouse input](docs/service-debugging.md) before
investigating connection stalls, missing mouse events, or shell startup crashes.
Capture both app and server logs: the shell script's built-in logcat follows
only the server PID. Follow Binder delivery, profile activation, service
construction, device open, native reads, and JNI callbacks in that order.
Log state transitions rather than every high-frequency input event, and
separate observed failures from suspected causes.

For editor persistence or overlay lifecycle changes, read
[Editor save lifecycle](docs/editor-lifecycle.md).
