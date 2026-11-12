---
name: deslop
description: Detect and rewrite AI-generated code slop into clean, production-quality code a senior engineer would write — preserving behavior exactly and without over-engineering. Use whenever the user says deslop, unslop, de-slop, clean up AI code, remove slop, mentions code that feels AI-generated, over-commented, over-engineered, bloated, or verbose, asks to simplify or tighten a file/diff/PR before review, or wants generated code rewritten to long-term maintainable best practice. Also use proactively when a large AI-generated change shows slop signs (narration comments, blanket try/catch, single-use helpers, dead imports) before committing or opening a PR. NOT for adding features, fixing bugs, or general refactors that change behavior.
---

# Deslop — rewrite AI slop into senior-quality code

Goal: the smallest, clearest code that does exactly what the original did. You are
removing noise, not redesigning. Two failure modes are equally bad: leaving slop in,
and "improving" the code into something different (new behavior, new abstractions,
golfed one-liners). The result should read like a strong senior engineer wrote it
on a calm day.

## Step 1 — Establish scope and a behavior baseline

1. Determine scope. Prefer the narrowest that matches the request:
   - "this diff / branch / PR" → git --no-pager diff <base>...HEAD plus uncommitted changes; only touch changed files.
   - named file(s) → those files only.
   - "this module/project" → confirm the directory list with the user before starting if it is large.
2. Capture a baseline BEFORE editing: run the project's tests/typecheck/build for the
   touched area (look for pytest/jest/vitest/go test/cargo test, tsconfig, CI config).
   Record what passes. If nothing runnable exists, note that and rely on review-only
   verification — and say so in the final report.
3. Read 2-3 neighboring files the slop code sits next to. Note the codebase's real
   conventions: comment density, error-handling style, naming, helper granularity.
   The codebase's norm — not your taste — is the target style.

## Step 2 — Detect slop

Scan the in-scope code against the taxonomy in references/slop-taxonomy.md
(read it — it has before/after examples and the boundary-vs-internal rule for
error handling). Categories in brief:

- **Comment slop** — narration, restating the code, section banners, docstrings that repeat the signature, reviewer-directed notes, emoji.
- **Defensive slop** — try/catch or null/undefined checks on internal, already-validated paths; fallback defaults that mask bugs; validating what the type system already guarantees.
- **Abstraction slop** — single-call helpers, premature interfaces/factories/registries, config options and "flexibility" nobody asked for, wrapper layers that only delegate.
- **Duplication slop** — reimplementing a util that already exists in the repo; near-identical blocks that should be one.
- **Dead weight** — unused imports/vars/params, unreachable branches, commented-out code, leftover debug prints/logs, placeholder TODOs for finished work.
- **Naming slop** — enhanced_/improved_/_v2/new_ prefixes, vague names (processData, handleStuff), names that don't match codebase conventions.
- **Compat slop** — backwards-compat shims, re-export aliases, deprecated paths kept "just in case" with no consumer.
- **Test slop** — asserts that can't fail, over-mocking until nothing real is tested, tests of implementation details instead of behavior.

Build a quick list of findings per file before rewriting — it becomes the report.

## Step 3 — Rewrite

Rules of the rewrite:

1. **Behavior is frozen — no judgment-call exceptions.** Same inputs → same outputs,
   same side effects, same public API, same error semantics. This includes behavior no
   test covers and behavior that looks like a bug: untested behavior is still behavior,
   and someone may depend on it. If removing something would change what ANY input
   produces (e.g. a swallowed exception now raising), do not change it — flag it in the
   report instead. When you catch yourself justifying a removal with "no test covers
   this", "callers shouldn't rely on it", or "this isn't a coherent contract", that
   reasoning is precisely the signal to keep the code and flag it. A deslop that traded
   a removal for a behavior change has failed, however well-argued the trade.
2. **Delete first, restructure second.** Most slop fixes are deletions. Only inline
   or merge code when it clearly reads better; never split or extract further.
3. **Keep legitimate engineering.** Error handling at real boundaries (user input,
   network, file I/O, external APIs) stays. Tests stay. Input validation at trust
   boundaries stays. When unsure whether a check is load-bearing, keep it and note it.
4. **Don't overshoot.** No golfing, no clever one-liners, no removing clarifying
   intermediate variables, no new abstractions to "do it properly". If the deslopped
   version is harder to read than the slop, you went too far.
5. Match the conventions you observed in Step 1.

## Step 4 — Verify and report

1. Re-run the exact baseline commands from Step 1. Everything that passed before must
   pass after. If something fails, fix your rewrite — never weaken or delete a test
   to get to green.
2. Report in this shape:

```text
## Deslop report
- Scope: <files/diff>
- Verified: <commands run, before/after result>
- Removed: <N> lines (<X>% of scope)
- By category: comment slop <n>, defensive slop <n>, ...
- Kept deliberately: <load-bearing checks/handlers you did NOT remove, and why>
- Flagged, not changed: <behavior-changing suggestions for the user to decide>
```
