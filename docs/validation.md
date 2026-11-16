# Validation before merge

Run the checks appropriate to the change before requesting review. For app code
or build changes, start with:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

When a PR contains device tests, also build and run them using the
[development guide](development.md). CI runs the host native suites, JVM tests
and debug builds. Review the workflow results and link to reports for failures.

For native changes, run the Linux host suites with CMake and a C++ compiler.
Set `JAVA_HOME` to your JDK and `ANDROID_NDK_HOME` to the configured NDK
directory before running the diagnostics suite.

```sh
cmake -S app/src/hostTest/cpp -B build/hostTest -DCMAKE_BUILD_TYPE=Debug
cmake --build build/hostTest --parallel
ctest --test-dir build/hostTest --output-on-failure

cmake -S app/src/hostTest/nativeDiagnostics -B build/hostNativeDiagnostics -DCMAKE_BUILD_TYPE=Debug
cmake --build build/hostNativeDiagnostics --parallel
ctest --test-dir build/hostNativeDiagnostics --output-on-failure --timeout 10
```

## Device coverage

For open and future PRs, add instrumentation where a change's behavior depends
on Android framework integration. Examples include Binder lifecycle and
permission handling, overlay and editor interactions, and JNI event delivery.
Put pure parsing and state transitions in JVM or host tests. For scenarios
that require specific hardware or privileges, run the relevant checks on a
matching device and record manual steps alongside the automated coverage. Reviewers
should ask for missing instrumentation before merging the affected PR.

In the PR, list the commands and results. For device runs, include the Android
version, device or Waydroid configuration, startup mode, and scenarios
exercised. Explicitly identify blocked or unverified scenarios. APK compilation
covers the build and packaging path. Device execution is a separate check.

## Human review

A human reviewer must inspect generated code and its validation before merge.
The PR author owns the diff and should be able to explain its behavior and test
coverage. Review the behavior at permission and process boundaries, error and
teardown paths, and effects on existing mappings. Automated checks and review
tools can provide evidence for that review.
