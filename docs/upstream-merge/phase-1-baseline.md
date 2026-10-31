# Upstream `dev` Merge — Phase 1 Baseline

Status: **upstream merge and source-level review fixes recorded; build, tests and device validation have not been run.**

Recorded: 2026-10-28. This checkpoint records immutable refs and verified source-level constraints before trial-merging upstream into the integration branch.

## Refs and rollback point

| Ref | Repository | Commit |
|---|---|---|
| Integration branch | `SoufianoDev/XtMapper:merge/upstream-dev-updates` | `8c82136a25903ac692728bd88045d1f964b19b50` |
| Upstream branch | `Xtr126/XtMapper:dev` | `2638df2099e778c08e7573c3d9b09006384ec48f` |
| Common base / merge base | Shared history | `8a9dc8146905747eba2869ffab30bfaea683b433` |
| Pre-merge rollback branch | `SoufianoDev/XtMapper:merge/pre-upstream-dev-2026-10-28` | `8c82136a25903ac692728bd88045d1f964b19b50` |

GitHub compare results show 19 commits on the integration side and 20 commits on the upstream side after the common base. The upstream ref was re-queried and resolves to `2638df20`; do not rely on an older cached branch snapshot.

## Integration behavior that must survive reconciliation

### Logging and process roles

- `App.onCreate()` initializes `XtLogger` for the regular app process.
- `RemoteService` initializes standalone logging before native library loading when running under UID 2000 (Shizuku) or UID 0 (root). A native-library load failure is recorded as fatal before the original throwable is rethrown.
- The privileged server's main-Looper uncaught-exception handler records the fatal exception, attempts the existing `am start ... --es data <crash details>` relay to `MainActivity`, then exits. This is a separate process path and must not be treated as covered solely because the app process initializes its logger.
- `RemoteServiceShell.main()` classifies the launch signal from `Environment.CLI_ARG` **before** `XtLogger.initStandalone(Role.SHELL)`, because the session header reads `Environment.name`.
- The logging configuration currently defines a 1,024-record bounded queue, drop-oldest overflow, 4 MiB per part, at most 8 parts per day, 14-day age retention, an 8 MiB total budget, 16 retained files, and a 5-second bounded flush. Preserve boundedness, rotation, retention, and session boundaries during integration.

### Launch signal and crash payload are different contracts

- `Environment.CLI_ARG` is `--cage-xtmapper-launch-signal`; `Environment.INTENT_EXTRA` is the boolean `cage_xtmapper_launch_signal`.
- The CLI signal is applied before standalone shell logging; the boolean Intent extra is applied by `MainActivity` only when that extra is present.
- `MainActivity` is exported. Its legacy `data` extra still uses `data=shell` as a shell-initialization hint and other non-null values as crash text, but neither value authenticates the caller.
- Current code has no trusted shell-initialization path: a caller-controlled extra alone cannot select a shell startup profile, auto-start the pointer, or reset the saved Shizuku mode. The `data=shell` marker only suppresses crash-payload replay.
- The crash payload is removed from the activity Intent after capture and displayed in a stable `DialogFragment` titled as unverified diagnostic text, with a 16 KiB display cap; activation prompts wait until the dialog is dismissed.
- The legacy marker always fails closed: Android never exposes the caller UID for this launch path (`getLaunchedFromUid()` stays `INVALID_UID` without identity sharing), so `data=shell` never triggers trusted auto-start or a persistent input-mode change. This is documented in `docs/waydroid-shell-startup.md` and still needs runtime validation.

### Build constraints

- Integration `app/build.gradle` enables core-library desugaring and includes `com.android.tools:desugar_jdk_libs:2.1.5`. The logging implementation uses `java.time` APIs while `minSdk` is 25, so this is a compatibility requirement for API 25–27, not optional dependency cleanup.
- Upstream currently has `testOptions { unitTests.returnDefaultValues = true }`; reconcile this deliberately with the integration's desugaring and test setup rather than blindly replacing either side's build configuration.

### Crash-report integration boundary

- `docs/crash-reporting.md` explicitly marks XtCrash routing and the cage-xtmapper host handoff as future work, not implemented behavior in this repository.
- XtCrash v1.0.0 is intentionally unpublished pending end-to-end compatibility validation with XtMapper and cage-xtmapper. Do not add or rely on the planned JitPack coordinates during this merge, and do not claim that an end-to-end host-reporting path exists.

## Initial change map

### Coordinated behavioral files

Review and reconcile both sides of these paths rather than accepting a wholesale copy from either side:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/xtr/keymapper/activity/MainActivity.kt`
- `app/src/main/java/xtr/keymapper/server/RemoteService.kt`
- `app/src/main/java/xtr/keymapper/server/RemoteServiceShell.kt`
- `app/src/main/java/xtr/keymapper/server/InputService.kt`
- `app/src/main/java/xtr/keymapper/keymap/KeymapProfiles.kt`
- `app/src/main/java/xtr/keymapper/ui/GameKeyMapper.kt`
- `app/build.gradle`

### Upstream feature areas to preserve and test

- Native keyboard-grab implementation and build wiring: `keyboard_grab.cpp`, `linux_key_table.h`, `Android.mk`, JNI/native declarations, and keyboard-routing behavior.
- Input/lifecycle work: touch-coordinate jitter, pointer teardown, input-device unplug/error handling, observer rescheduling, service/Binder lifecycle.
- Profile/editor work: default profile selection for shell startup, profile scaling/designation, ShiftKey support, editor layout/metrics, and localization.
- Upstream unit tests should be retained and adapted to the final integration behavior, not discarded to make compilation pass.

### Low-coupling candidates

Documentation, translations, independent tests, and the `evdev_common.cpp` directory-handling fix can be considered for early adoption after checking their exact diff against the common base. “Low-coupling” is a review category, not permission to copy unexamined files.

## Verification state

This is a GitHub source audit only. No build, unit test, release build, device test, or Waydroid test has been run as part of this checkpoint. These must be reported as **not run** until evidenced by a successful run.

## Trial merge result (2026-10-28)

A disposable trial merge was created on `merge/trial-upstream-dev-2026-10-28`:

- Initial merge commit: `73174b9175de3864030ec475b3011a214b9a6d84` (parents: integration checkpoint `c067b0af587ca9e837532264b6002760135bd67c` and upstream `2638df2099e778c08e7573c3d9b09006384ec48f`).
- Follow-up trial fix: `c24718c0a7cfe58c1c5e2065d1a99d29bbbcd4e4` restores the README credits table header. Upstream `dev` had changed it to a blank `|  |  |` header.
- Follow-up trial fix: `545c788011b67dedb3885c083478ebf19e4abf99` qualifies the `geteventProcess` property as `java.lang.Process`. The integration imports `android.os.Process` for UID classification, so the unqualified type from upstream would resolve to the wrong class and fail to provide the subprocess methods.

The merged tree contains upstream changes for 58 changed paths (51 non-overlapping paths plus the seven behaviorally reviewed overlapping files); no upstream deletions were found. The tree includes the native keyboard-grab implementation, touch-jitter support, quick-settings tile, editor/profile changes, and upstream unit tests.

Source-level structural checks were run on all seven overlapping files: no conflict markers or duplicate imports were found, and each file's opening/closing brace count balanced. Additional targeted checks confirmed that desugaring and its dependency remain, JUnit is declared once, the app Application hook remains, the launch signal is separate from `data=shell`, and logger initialization/session close paths remain present. These checks do **not** replace Kotlin compilation or tests.

## Verification status

- `app:assembleDebug`: **not run**
- `app:assembleRelease`: **not run**
- `app:testDebugUnitTest`: **not run**
- Device/Waydroid behavior and native keyboard-grab tests: **not run**
- GitHub status checks: none reported for the trial merge commit.
- The only observed Actions run was Crowdin, which was skipped. It is not build verification.

The trial is therefore a source-reconciliation checkpoint, **not a verified build or release candidate**. No XtCrash tag, release, or publishing action was performed.

## Next required step

Run the debug assembly and JVM unit tests on a full local checkout of the trial branch, then inspect and resolve compiler/test failures before treating the merge as validated. Run release assembly and device/native behavior checks afterward. Keep the proof-of-concept host-reporting/security tests separate from this merge. Do not tag or publish XtCrash as part of this task.

## Follow-up source corrections after the initial CodeRabbit checkpoint

The following small, reversible commits were added after merge commit `a316da06279429b76f580e5c3af937ab4317e870`:

- `b67a7fedd282ed0675c0d1aff4578dcb7d00ba96`: combined the two `MainActivity.onResume()` bodies into one override, retaining both the `TouchPointer.isRunning` state refresh and the guarded file-logging enablement.
- `a7ca6ba620142b417df7e082f13c6a425732ae24`: limited the Shizuku-to-Root preference reset to `isShellInit`, so arbitrary crash-text values no longer trigger that persisted mode change. This does not authenticate an external `data=shell` Intent; the exported activity's origin/trust boundary remains open.
- `facb816284c29af39a87e98011d7f83c96aaff8e` and `3bbe7f532edb157558d4f995db86d6d13bee097d`: pass the initial Wayland mode into `RemoteService` before its initializer can launch the input reader; existing one-argument Kotlin construction remains available through the default parameter and generated overload.
- `29d87cf83aaf6da8b79ef54255f6f63b8f107b15`: route `InputService` keyboard-grab/input exception diagnostics through `XtLogger` rather than direct Android `Log` calls.
- `bc3815d42b757f4f4cabfd43eca3f093ec8c789c`: route `RemoteService.start_getevent()` input-reader exception diagnostics through `XtLogger`.

Static checks after these edits confirmed one `onResume()` declaration and one `super.onResume()` call, the Shizuku reset guard, the initial Wayland constructor argument, no direct `Log.e/w/i/d/v` calls in `InputService.kt`, and no direct `Log.e` calls in the `start_getevent()` block. These are source checks only.

## Follow-up source corrections after the full CodeRabbit review

In addition to the earlier checkpoint fixes above, the latest follow-up commits address the full review's actionable comments:

- Corrected the overlay dimensions lookup and clipboard import/reload/save path in `EditorUI.kt`.
- Separated untrusted Intent extras from trusted shell initialization, consumed crash data once, and moved crash rendering to a lifecycle-managed dialog in `MainActivity.kt`.
- Repaired profile designations after rename/delete and stale fallback, preserving the last-selected profile on rename; regression tests cover designation cleanup and stale entries.
- Clamped touch-jitter output to the last valid pixel and tightened the boundary test.
- Avoided starting a redundant stop service from the Quick Settings tile and ensured `ACTION_STOP` always stops the matching service start.
- Synchronized shift-key transition state, fixed block-keyboard shortcut capture, and synchronized the jitter slider with Advanced settings.
- Routed important diagnostics from `Input.kt`, `RemoteService.kt`, `RemoteServiceShell.kt`, `ActivityObserverService.kt`, and `MouseEventHandler.kt` through XtLogger. Pointer overflow now returns instead of proceeding with an invalid pointer index. Session END statuses distinguish crash relay, startup failure and unexpected exit.
- Restored missing Spanish/French/Portuguese/Russian import-error strings, localized ShiftKey transition labels, and corrected the README log-path claim and merge checkpoint status.

## Current verification boundary

- Regression tests were updated/added for profile designation cleanup and exclusive touch-jitter bounds, but none have been executed.
- No Gradle compile, debug/release assembly, JVM unit test, Android device/Waydroid run, native/JNI check or cold-start Wayland test has been performed.
- No GitHub Actions workflow runs or status checks are currently reported for the latest head. A new CodeRabbit review was requested after the source corrections; its findings still need to be assessed when returned.

Keep PR #5 as a draft. Do not claim the integration is build-verified or merge it until compilation, tests and required runtime checks pass.
