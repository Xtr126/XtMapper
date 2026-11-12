---
name: hush
description: Use on every code write, edit, refactor or review. Keeps useless comments out of code, removes any that slipped in (only on lines you changed), then formats the touched files with the project's own formatter. Also use when the user mentions redundant or AI-generated comments, comment noise, comment cleanup, or formatting after edits.
---

# Hush

Models comment by reflex, learned from training data, not from what a reader needs. A comment that restates code costs reading time and goes stale silently. So the default is no comment, and a comment must earn its place.

Precedence: an explicit instruction from the user or the project ("comment everything", "never comment") beats this skill. Follow it.

## While writing or editing

Write a comment only for what the code cannot show: why this approach, a hidden constraint, a workaround (with link or ticket), a unit, an invariant, a surprising edge case. One line.

If a comment exists only because a name is unclear, rename the thing, but only if it is local or private. Never rename a public name to avoid a comment.

Never write:
- comments that restate the next line
- comments that narrate your edit ("moved from utils", "now uses X", "removed old logic"). Those belong in the chat or the commit message
- banners, and docstrings that only repeat the signature
- commented-out code
- guesses about intent that the code does not support

## Cleanup pass (end of every change)

Scope: only comments inside code you added or changed this session. Judge by your own edits; git diff helps locate them but also shows the user's earlier uncommitted work, so don't assume every hunk is yours. Without git, rely on your own edit record. Leave pre-existing comments alone. Leave generated, vendored and lock files alone. For a repo-wide cleanup, list what you would remove and ask once.

In a chat with no files, apply the same rules to the code you write in your reply, or to code the user pastes and asks you to clean.

Delete a comment in scope if it matches the "never write" list. Keep it if any of these is true:
- it explains why, or states a constraint, workaround or invariant
- a tool reads it: a directive (eslint-disable, noqa, type: ignore, @ts-*, nolint, pragma, fmt: off, prettier-ignore, shellcheck, rubocop), a //go: or #[...] style marker, a magic comment (webpackChunkName), a coverage marker, a shebang, an encoding or frozen_string_literal line, a #region
- it is a license header
- it is a doc comment the project requires or its tooling consumes (Python docstrings are runtime data and doctests; JSDoc may carry types; Rust /// and Javadoc feed generated docs). Check neighbouring code and the lint config (pydocstyle, ruff D, require-jsdoc, Checkstyle) before touching one
- it is a TODO with an owner or issue link

When unsure, keep it and cut it to one line. A wrongly deleted "why" is lost knowledge; a short leftover costs little.

Delete with care: a # or // inside a string, regex or template is not a comment, and a trailing comment shares its line with code. After deleting, check the diff shows only comment and whitespace changes, then run the project's existing fast check (lint, typecheck or tests) if one exists. If deleting a comment breaks lint, restore it.

## Format

Format only the files you touched, with the project's own tool, found from config and not habit: .prettierrc or a format script in package.json, ruff or black in pyproject.toml, rustfmt.toml, gofmt, .editorconfig, a Makefile target. Run it after removing comments, because formatters reflow around them.

No formatter in the project: do not invent a style. Just collapse the double blank lines and trailing whitespace your deletions left.

If the host agent already formats after edits (a hook, or a built-in formatter), skip this step; running twice only adds noise. Without a shell (plain chat), skip it and say so.

This skill is advisory. If the user needs guaranteed formatting, tell them a post-edit formatter hook or a CI formatter is the only deterministic option.

## Report

End with one line: comments removed, comments kept and why (e.g. "2 directives"), formatter run or none found.
