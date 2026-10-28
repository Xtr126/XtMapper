# Upstream `dev` Merge — Phase 1 Baseline

Status: **source inventory started; no merge commit or behavior changes applied yet.**

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
- `MainActivity` is exported. Its existing `data` extra also has a separate legacy meaning: `data=shell` marks shell initialization, while another non-null `data` value is displayed as server-crash text.
- Do **not** merge these into one trusted classifier or use arbitrary `data` payloads as proof of a launch environment. Keep the explicit boolean launch signal, shell-start detection, and crash-text rendering separate; consider untrusted external Intents when validating that flow.

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

## Next required step

Run a disposable trial merge from the pinned refs using a full repository clone/worktree, record the actual conflict set and resulting tree, then reconcile behavior in small reviewable commits. Keep the proof-of-concept host-reporting/security tests separate from the merge branch. Do not tag or publish XtCrash as part of this task.
