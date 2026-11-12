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

The skills below are developer-environment tools, not project dependencies.
Do not vendor their skill files, reference documents, or licenses into this
repository. Resolve and prepare them in the environment of the agent that is
actually doing the work.

### Skill availability and freshness — required before coding

At the start of every source-code task:

1. Inspect the current agent's actual skill directories and skill registry.
   Check the locations and discovery mechanism supported by that agent (for
   example, `~/.agents/skills/`, `~/.claude/skills/`, `~/.opencode/skills/`,
   workspace-level skill directories, or the agent's configured equivalents).
   Do not assume one path applies to every agent.
2. Check whether all three skills are installed and discoverable by this agent:
   `ponytail`, `hush`, and `deslop`. A file existing somewhere on disk is
   not enough if the current agent cannot discover or load it.
3. Resolve the latest official source revision at task time. Do not trust a
   cached version, a remembered version, a branch name alone, or a copy in
   this repository. Compare the installed skill content and required reference
   files with the current upstream source (prefer source commit SHA and file
   hashes; if the installation has no version metadata, compare contents).
4. If a skill is missing, install the latest official version into the correct
   skill directory for the current agent. If it is outdated, update that
   installation in place. Use the agent's supported skill installer when
   available; otherwise fetch the canonical source and install its skill
   directory, including required references and assets. Do not add these files
   to the XtMapper working tree or commit them.
5. Verify that the current agent can load each installed skill and that the
   installed content matches the resolved source revision. If network access,
   permissions, or the agent runtime prevents installation or verification,
   report the exact blocker and do not claim the skill is current or available.
6. Re-check freshness when starting a new task/session; do not assume a skill
   remains current just because it was checked in an earlier session.

### Canonical skill sources

Resolve the latest revision from these canonical repositories at runtime:

- Ponytail: `https://github.com/DietrichGebert/ponytail`,
  skill directory `skills/ponytail/`, source branch `main`.
- Hush: `https://github.com/SoufianoDev/hush`,
  skill directory `skills/hush/`, source branch `dev`. This is Soufiano's
  project-owned Hush skill; do not substitute another author's skill with the
  same name.
- Deslop: `https://github.com/1Mangesh1/deslop`,
  skill directory `skills/deslop/`, source branch `main`. Install the
  complete skill directory, including `references/slop-taxonomy.md`.

The branch names above identify the canonical source lines, not fixed versions.
Always resolve their latest commit and files before checking or installing.

### Ponytail — mandatory Ultra mode

Use Ponytail in `ultra` mode for every coding task. Activate
`/ponytail ultra` when the agent supports the command; otherwise follow the
installed skill's Ultra rules directly. Keep this mode active for the whole
task.

Before writing, inspect the touched implementation, callers, tests, and
relevant configuration. Challenge scope that adds features, abstractions,
options, dependencies, or flexibility without a demonstrated need. Choose the
smallest complete change, not the smallest incomplete patch. Preserve
correctness, validation at trust boundaries, error handling, data integrity,
security, accessibility, and hardware-specific behavior.

### Hush — apply while writing, not as a later repair

Load and apply Soufiano's Hush skill while writing every code line, not merely
as a cleanup step after implementation. The default is no comment. Prefer a
precise name over a comment. Add a comment only for information the code
cannot express: a non-obvious reason, constraint, workaround, unit, invariant,
or surprising edge case. Keep ordinary comments to one line.

Never narrate code, restate the next statement, annotate obvious variables,
add section banners, or describe the current edit. Do not create comment
clusters: several comments above a simple variable, or a separate comment for
each statement in a short function, is unacceptable. A three-line function
does not need seven comments. Do not add comments as placeholders with the
intention of removing them later.

Preserve license headers, tooling directives, required documentation, and
comments that record real invariants or constraints. In this Android
application, Kotlin `public` visibility alone does not make a declaration a
public library API; do not add KDoc to every public declaration. Document a
genuinely externally consumed API or a surface required by project
tooling/conventions.

### Deslop — final code-quality audit

Use Deslop after implementation and before final review or commit. Read its
complete installed skill and the referenced slop taxonomy. Scope the audit to
code changed for the current task; this branch contains earlier migration
work, so do not run a broad cleanup across the entire PR unless the user
explicitly requests it.

Capture the relevant test/build baseline before cleanup and rerun the same
checks afterward. Remove only noise introduced by the current task. Preserve
behavior and error semantics exactly. Never remove real boundary validation,
data-loss protection, security checks, Binder/lifecycle safeguards, concurrency
controls, or meaningful tests. When removing a check could change behavior,
keep it and report it as a finding instead.

### Required order

1. Verify skill availability and freshness in the current agent environment;
   install or update the skills there if necessary.
2. Load Ponytail and apply Ultra scope review before editing.
3. Load Hush and apply its comment rules during every code edit.
4. Implement the smallest complete change and add focused tests for
   non-trivial behavior.
5. Run Deslop against only the current task's changed code, then run the
   relevant verification again.
6. Report the skill revisions used, what was checked, what remains
   unverified, and any slop deliberately kept because removing it would
   change behavior.

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
