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

## Native keyboard labels

`app/Android.mk` generates `linux_key_table.h` under the per-ABI native object
output directory using the configured NDK's `linux/input-event-codes.h` and
bundled Python (`HOST_PYTHON`). No separate Python installation or network
access is needed for generation. Both Gradle and direct `ndk-build` builds use
this rule; changing the script or NDK header regenerates the lookup.

The generator adapts the label extraction from [AOSP getevent](https://android.googlesource.com/platform/system/core/+/android16-qpr2-release/toolbox/generate-input.h-labels.py).
It keeps canonical literal `KEY_*` definitions, excludes aliases, reserved/range
markers and `BTN_*`, and returns null for unknown codes. Labels describe evdev
codes; this does not translate XKB symbols or migrate legacy Cage profiles.

Coverage follows the selected NDK. NDK `28.0.13004108` lacks seven labels from
the former checked-in table: `KEY_LINK_PHONE`, `KEY_EPRIVACY_SCREEN_ON`,
`KEY_EPRIVACY_SCREEN_OFF`, `KEY_ACTION_ON_SELECTION`, `KEY_CONTEXTUAL_INSERT`,
`KEY_CONTEXTUAL_QUERY`, and `KEY_PERFORMANCE`. Those codes are now ignored by
keyboard capture, like other unknown codes, until the NDK supplies their definitions.

Run generator regression checks with a host Python 3 and C++ compiler:

```sh
python3 -m unittest discover -s app/src/test/python -v
```
