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

## Agent skills and code-writing rules

Read and apply the repository-local skills under `.agents/skills/` for every
source-code task. Do not rely on skill names alone; load the referenced
`SKILL.md` before editing.

### Ponytail — mandatory Ultra mode

Use [Ponytail](.agents/skills/ponytail/SKILL.md) in `ultra` mode for every
coding task. If the agent supports the command, activate `/ponytail ultra`;
otherwise follow the skill's Ultra rules directly. Keep this mode active for
the whole task.

Before writing, inspect the touched implementation, callers, tests, and relevant
configuration. Challenge scope that adds features, abstractions, options,
dependencies, or flexibility without a demonstrated need. Choose the smallest
complete change, not the smallest incomplete patch. Preserve correctness,
validation at trust boundaries, error handling, data integrity, security,
accessibility, and hardware-specific behavior.

### Hush — apply while writing, not as a later repair

[Hush](.agents/skills/hush/SKILL.md) is the project's source of truth for comment
style and must be applied as each line is written or edited. The default is no
comment. Prefer a precise name over a comment. Add a comment only for information
the code cannot express: a non-obvious reason, constraint, workaround, unit,
invariant, or surprising edge case. Keep ordinary comments to one line.

Never narrate code, restate the next statement, annotate obvious variables, add
section banners, or describe the current edit. Do not create comment clusters:
several comments above a simple variable, or a separate comment for each
statement in a short function, is unacceptable. A three-line function does not
need seven comments. Do not add comments as placeholders with the intention of
removing them later.

Preserve license headers, tooling directives, required documentation, and
comments that record real invariants or constraints. In this Android application,
Kotlin `public` visibility alone does not make a declaration a public library
API; do not add KDoc to every public declaration. Document a genuinely
externally consumed API or a surface required by project tooling/conventions.

At the end of each task, review only comments in code you personally changed
during that task. Leave pre-existing, generated, vendored, and lock-file content
alone. Format only touched files with a formatter already configured by the
project; do not introduce a formatter or reformat unrelated files.

### Deslop — final code-quality audit

Use [Deslop](.agents/skills/deslop/SKILL.md) after implementation and before
final review or commit. Read its
[slop taxonomy](.agents/skills/deslop/references/slop-taxonomy.md). Scope the
audit to code changed for the current task; this branch contains earlier
migration work, so do not run a broad cleanup across the entire PR unless the
user explicitly requests it.

Capture the relevant test/build baseline before cleanup and rerun the same
checks afterward. Remove only noise introduced by the current task. Preserve
behavior and error semantics exactly. Never remove real boundary validation,
data-loss protection, security checks, Binder/lifecycle safeguards, concurrency
controls, or meaningful tests. When removing a check could change behavior,
keep it and report it as a finding instead.

### Required order

1. Read Ponytail and apply Ultra scope review before editing.
2. Read Hush and apply its comment rules during every code edit.
3. Implement the smallest complete change and add focused tests for non-trivial
   behavior.
4. Run Deslop against only the current task's changed code, then run the
   relevant verification again.
5. Report what was checked, what remains unverified, and any slop deliberately
   kept because removing it would change behavior.

### Skill sources

- Ponytail: `DietrichGebert/ponytail` (MIT).
- Hush: `SoufianoDev/hush` (`dev` branch; project-owned source).
- Deslop: `1Mangesh1/deslop` (MIT; code-focused Deslop skill).

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
