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

### Supported coding-agent environments

The project may be worked on through different agent hosts and IDE integrations.
The runtime host determines which instruction files and skill directories are
actually discovered. Do not assume that every tool reads `AGENTS.md`, uses the
same home directory, or supports the same skill-installation mechanism.

Use this compatibility map as a starting point, then verify it against the
installed tool version and its official documentation before installing or
updating anything.

| Agent or host | Project instructions | Project skill locations | User-level skill locations |
|---|---|---|---|
| **OpenAI Codex (CLI and IDE integrations)** | `AGENTS.md` | `.agents/skills/` | `~/.agents/skills/` or the active `$CODEX_HOME/skills/` |
| **Cursor (Agent and CLI)** | Root `AGENTS.md`; also `.cursor/rules/*.mdc` for scoped rules | `.agents/skills/`, `.cursor/skills/`; compatible `.claude/skills/` and `.codex/skills/` | `~/.agents/skills/`, `~/.cursor/skills/` (and compatible Claude/Codex locations) |
| **Google Antigravity (App and CLI)** | `AGENTS.md`, `GEMINI.md`, or `.agents/rules/*.md` | `.agents/skills/` | `~/.gemini/config/skills/`; Antigravity CLI may also use `~/.gemini/antigravity-cli/skills/` |
| **Claude Code** | `CLAUDE.md`, `.claude/CLAUDE.md`, and `.claude/rules/*.md`; do not assume root `AGENTS.md` is loaded automatically | `.claude/skills/` | `~/.claude/skills/` |
| **OpenCode** | `AGENTS.md` | `.opencode/skills/`, `.agents/skills/`, `.claude/skills/` | `~/.config/opencode/skills/`, `~/.agents/skills/`, `~/.claude/skills/` |
| **Gemini CLI** | `GEMINI.md` by default; `AGENTS.md` can be added through `context.fileName` in Gemini settings | `.gemini/skills/`, `.agents/skills/` | `~/.gemini/skills/`, `~/.agents/skills/` |
| **GitHub Copilot (VS Code, CLI, app, and cloud agent)** | `AGENTS.md` on supported surfaces; `.github/copilot-instructions.md` for repository-wide Copilot instructions | `.github/skills/`, `.agents/skills/`, `.claude/skills/` | `~/.copilot/skills/`, `~/.agents/skills/` |
| **Cline** | `AGENTS.md`, `.clinerules/`, or `.cline/rules/` | Use the skill locations exposed by the installed Cline version; check its Skills UI/CLI and whether it discovers `.agents/skills/` | Cline documents `~/.cline/skills/`; verify the active platform's current path |
| **Roo Code** | `AGENTS.md`, `.roo/rules/`, or `.roorules` | `.roo/skills/`, `.agents/skills/` | `~/.roo/skills/`, `~/.agents/skills/` |
| **Windsurf / Cascade** | `.windsurf/rules/*.md`; `.windsurfrules` is a legacy format. Check the current Windsurf/Devin surface for its `AGENTS.md` support | Verify the active version's Agent Skills support and discovery path; do not guess it from another IDE | Check the current host's user skill directory and sync behavior |
| **JetBrains Junie (IDE and CLI)** | Root `AGENTS.md` or `.junie/AGENTS.md`; note that `.junie/AGENTS.md` takes precedence and may be used exclusively | `.junie/skills/`, `.agents/skills/` | `~/.junie/skills/`, `~/.agents/skills/` |
| **Aider** | Does not rely on automatic `AGENTS.md` discovery; load it with `--read AGENTS.md` or configure it in `.aider.conf.yml` | No assumed native Agent Skills discovery; explicitly load only the relevant `SKILL.md` or use a supported integration | Load personal conventions/skills explicitly using Aider's documented read mechanism |

**Claude Code vs. Google Cloud Code:** Claude Code is an AI coding agent and uses
the Claude Code paths above. Google Cloud Code is a separate IDE extension for
Kubernetes and Cloud Run development; when it is used with Gemini Code Assist,
follow the instruction and skill capabilities of the actual Gemini Code Assist
host rather than treating Cloud Code as a separate Agent Skills runtime.

#### Cross-agent instruction policy

- Keep this root `AGENTS.md` as XtMapper's canonical project guidance.
- Prefer native `AGENTS.md` support where the active host provides it.
- When a host requires another instruction filename (for example
  `CLAUDE.md` or `GEMINI.md`), use a small bridge/import to this file when
  that host supports imports. Do not copy the entire ruleset into multiple
  files where the copies can drift.
- For GitHub Copilot, use its repository instructions file only when the active
  Copilot surface or version does not consume the root `AGENTS.md`.
- For Windsurf, Aider, or any other host whose behavior differs by version or
  surface, inspect its current official documentation and runtime settings.
  Never claim compatibility merely because it can read Markdown.
- Distinguish an IDE extension from its underlying agent. Examples include
  Google Cloud Code with Gemini Code Assist and VS Code with a selected agent
  harness. Install skills for the agent that executes the work, not just for
  the editor shell.
- If the agent delegates work to subagents or runs tasks in remote/cloud
  workers, check skill availability in each execution environment. A skill
  installed only on the developer's workstation is not automatically present
  in a cloud container, remote SSH host, or another agent's sandbox.
- Do not install the same skill into every possible directory. Use the
  currently active host's supported project or user scope, avoid duplicate
  skill IDs with conflicting versions, and verify which copy wins precedence.

#### Official compatibility references

Check these vendor references for current behavior and paths:

- Codex: https://developers.openai.com/codex/guides/agents-md and
  https://developers.openai.com/codex/skills
- Cursor: https://docs.cursor.com/context/rules-for-ai and
  https://docs.cursor.com/skills
- Antigravity: https://antigravity.google/docs/rules and
  https://antigravity.google/docs/skills
- Claude Code: https://code.claude.com/docs/en/memory and
  https://code.claude.com/docs/en/skills
- OpenCode: https://opencode.ai/docs/instructions and
  https://opencode.ai/docs/skills
- Gemini CLI: https://geminicli.com/docs/cli/gemini-md and
  https://geminicli.com/docs/cli/using-agent-skills
- GitHub Copilot: https://docs.github.com/en/copilot/reference/customization-cheat-sheet and
  https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills
- Cline: https://docs.cline.bot/customization/cline-rules and
  https://docs.cline.bot/customization/skills
- Roo Code: https://docs.roocode.com/features/custom-instructions and
  https://docs.roocode.com/features/skills
- Windsurf: https://docs.windsurf.com/
- Junie: https://junie.jetbrains.com/docs/guidelines-and-memory.html and
  https://junie.jetbrains.com/docs/agent-skills.html
- Aider: https://aider.chat/docs/usage/conventions.html
- Google Cloud Code: https://docs.cloud.google.com/code/docs

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
