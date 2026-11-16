# Development and instrumentation

Follow the [build and signing setup](../BUILDING.md). Use the checked-in Gradle
wrapper. Point `ANDROID_HOME` or an ignored `local.properties` at the SDK.
Keep machine-specific paths and signing files out of commits.

## Android device setup

1. Run `adb devices -l` and authorize the debug host. If multiple devices are
   connected, target one with `adb -s SERIAL` and set `ANDROID_SERIAL` for
   Gradle device commands.
2. Unlock the device for UI and overlay tests. Grant overlay permission and set up
   the applicable privileged startup method. Test the actual configuration the
   PR affects, such as a rooted device, Shizuku, or shell startup on Waydroid.
3. Use a device with a keyboard and mouse when validating real device discovery,
   grabs, or event delivery. A test that injects input can cover Android
   integration, while physical-device checks cover the hardware path.

## Android tests

Put tests under `app/src/androidTest/java/` with packages matching the code
under test. Use AndroidX Test's `AndroidJUnitRunner` and assert observable
behavior. Restore any app state or resources the test changes.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Gradle installs both APKs for connected tests. The debug app ID is
`xtr.keymapper.debug` and the instrumentation APK ID is `xtr.keymapper.debug.test`.
To run a focused test, pass its fully qualified class or `Class#method` as a
runner argument:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=fully.qualified.TestClass
```

For timing or startup failures, capture app and server logs as described in
[service debugging](service-debugging.md). See the
[validation guide](validation.md) for what to include in the PR.
