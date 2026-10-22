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
