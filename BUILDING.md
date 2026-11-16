# Building XtMapper

## Prerequisites

- JDK 21. The Gradle daemon JVM configuration requests this version and can provision it through Foojay.
- Android SDK Platform 37.0, Build Tools `36.0.0`, and Android NDK `30.0.16248370`, as configured by the app module.
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

The debug variant uses the standard debug key. The checked-in
`keystore.properties` supplies placeholder release settings for Gradle configuration.
Keep local signing credentials out of commits.

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

Coverage follows the configured NDK headers. Keyboard capture ignores codes
whose labels are absent from those headers.

Run generator checks with host Python 3 and a C++ compiler, and Kotlin checks
with Gradle:

```sh
python3 -m unittest discover -s app/src/test/python -v
./gradlew :app:testDebugUnitTest --tests xtr.keymapper.InputEventLabelsTest --console=plain
```
