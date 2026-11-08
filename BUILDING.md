# Building XtMapper

## Prerequisites

- JDK 21. The Gradle daemon JVM configuration requests this version and can provision it through Foojay.
- Android SDK Platform 37 and Android NDK `28.0.13004108`, as configured by the app module.
- An Android SDK installation with its licenses accepted. Gradle downloads the wrapper and project dependencies on the first build.

Open the project in Android Studio to install missing SDK components, or install them with the Android SDK command-line tools.

## Debug build

Run the following from the project root:

```sh
./gradlew assembleDebug
```

On Windows, run:

```bat
gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Signing configuration

The app module loads `keystore.properties` from the project root whenever Gradle configures the project. Create this ignored local file before running Gradle, including for a debug build. The debug variant does not use the release signing configuration, but the file is still loaded during configuration.

The release build uses these properties:

```properties
keyAlias=your-key-alias
keyPassword=your-key-password
storeFile=/path/to/your/keystore.jks
storePassword=your-keystore-password
```

Keep the keystore and its passwords private. The release variant is signed with the configured keystore. Build it from the project root with `./gradlew assembleRelease` (or `gradlew.bat assembleRelease` on Windows).

## Generated input codes and labels

`app/src/main/cpp/generate-input.h-labels.py` adapts [AOSP getevent's generator](https://android.googlesource.com/platform/system/core/+/android16-qpr2-release/toolbox/generate-input.h-labels.py).
Both build paths use the configured NDK's `linux/input.h`,
`linux/input-event-codes.h`, and bundled Python; generation needs no separate
Python installation or network access.

- ndk-build generates `input.h-labels.h` in its per-ABI object directory.
- Gradle's `generateInputLabels` task generates `InputEventCodes.kt` and
  `InputEventLabels.kt` under `app/build/generated/source/inputLabels`, registers
  the source directory, and runs before Kotlin/Java compilation.

The shared generator covers input properties, event types, synchronization,
keyboard/mouse/gamepad buttons, relative and absolute axes, switches,
miscellaneous events, LEDs, repeat, sound, multitouch tools, and force feedback.
Constants include aliases and range/count expressions. Label tables use the
first literal name for each value, like AOSP; aliases and range/count markers
do not override labels. Event lookups select a table by event type and return
null for unknown values. These are evdev names, without XKB translation or
legacy Cage profile migration.

Coverage follows the configured NDK. NDK `28.0.13004108` lacks seven keyboard
labels from the former table: `KEY_LINK_PHONE`, `KEY_EPRIVACY_SCREEN_ON`,
`KEY_EPRIVACY_SCREEN_OFF`, `KEY_ACTION_ON_SELECTION`, `KEY_CONTEXTUAL_INSERT`,
`KEY_CONTEXTUAL_QUERY`, and `KEY_PERFORMANCE`. Keyboard capture ignores those
unknown codes until the NDK supplies their definitions.

Run generator checks with host Python 3 and a C++ compiler, and Kotlin checks
with Gradle:

```sh
python3 -m unittest discover -s app/src/test/python -v
./gradlew :app:testDebugUnitTest --tests xtr.keymapper.InputEventLabelsTest --console=plain
```
