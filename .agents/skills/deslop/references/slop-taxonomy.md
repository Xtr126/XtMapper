# Slop taxonomy — patterns with before/after

The unifying test for every category: **would a strong senior engineer, writing this
file from scratch in this codebase, have produced this line?** If no, it's slop.
The second test protects against overshooting: **does removing it change behavior or
hide intent?** If yes, keep it.

## 1. Comment slop

Delete comments that narrate, restate, or talk to a reviewer. Keep comments that
carry information the code cannot: constraints, gotchas, links to decisions, the why.

```python
# BEFORE
# Initialize the counter to zero
count = 0
# Loop through each item in the list
for item in items:
    # Increment the counter
    count += 1

# AFTER
count = len(items)
```

Also slop: section banners (# ===== HELPERS =====), docstrings that repeat the
signature ("Takes a user and returns the user's name."), changelog comments
(# Updated to fix the bug), emoji.

Keep: # NB: API returns cents, not dollars — that's a why.

## 2. Defensive slop

The rule is **boundary vs internal**. Validate and handle errors where untrusted data
enters (user input, HTTP, files, env, external APIs). Inside the trust boundary,
checks for states that cannot occur are noise that hides real logic.

```python
# BEFORE — internal function, callers all pass a validated User
def get_display_name(user):
    try:
        if user is None:
            return ""
        if not hasattr(user, "name"):
            return ""
        name = user.name
        if name is None or not isinstance(name, str):
            return ""
        return name.strip()
    except Exception:
        return ""

# AFTER
def get_display_name(user):
    return user.name.strip()
```

Especially harmful: except Exception: pass / return default — it converts crashes
(debuggable) into silent wrong behavior (not debuggable). But removing a REACHABLE
swallow IS a behavior change (inputs that used to get the default now raise) — flag it,
never apply it, even if you're confident the swallow is a bug and no test covers it.
Only delete a catch when the wrapped code provably cannot raise (then nothing changes).

Keep: retry/timeout handling on network calls, validation in request handlers,
checks the codebase consistently applies (its norm wins).

## 3. Abstraction slop

```javascript
// BEFORE
function getUserName(user) { return user.name; }
function formatUserName(name) { return name.trim(); }
function processUserName(user) { return formatUserName(getUserName(user)); }
const displayName = processUserName(user);

// AFTER
const displayName = user.name.trim();
```

Also slop: an interface/base class with one implementation; a factory that builds one
thing; options parameters with one call site that never passes them; a "Manager"/
"Service"/"Handler" class wrapping two functions; layers that only delegate.

Keep: abstractions with 2+ real, current users; patterns the codebase already uses
(if everything is a service class, match it).

## 4. Duplication slop

AI assistants reimplement instead of searching. Before accepting any utility-shaped
code (date formatting, slugify, debounce, retry, deep-merge, validation), search the
repo for an existing implementation (rg -i 'formatDate|format_date'). Use the
existing one even if you'd have written it differently.

## 5. Dead weight

- Imports/variables/parameters nothing uses.
- Branches that can't be reached (else after exhaustive returns, duplicate conditions).
- Commented-out code — git remembers; delete it.
- Debug leftovers: console.log, print(f"DEBUG ..."), temporary timers.
- // TODO: implement above implemented code; placeholder docstrings.

Scope discipline: delete dead weight introduced by the slop code itself. Pre-existing
dead code elsewhere in the file gets flagged in the report, not deleted, unless the
user asked for a whole-file cleanup.

## 6. Naming slop

- enhanced_, improved_, new_, _v2, final_ prefixes/suffixes — name things for
  what they are, not their revision history.
- Vague verbs: processData, handleItem, doWork, manageState — name the actual
  transformation (parseInvoiceRows, dedupeByEmail).
- Style mismatched to codebase (camelCase in a snake_case module).

Renames must stay inside the scope: rename only symbols the slop code introduced.
Renaming public/exported symbols is an API change — flag, don't do.

## 7. Compat slop

Shims nobody asked for: re-export aliases "for backwards compatibility" on brand-new
code, deprecated wrappers with zero consumers, version checks for versions the project
doesn't support. Verify with a repo-wide search that nothing consumes them, then delete.
If something DOES consume them, they're not slop — leave them.

## 8. Test slop

- Assertions that cannot fail: assert result is not None right after constructing it;
  expect(true).toBe(true); asserting a mock returned what you mocked it to return.
- Over-mocking: every collaborator mocked so the test exercises nothing real.
- Implementation-detail tests: asserting a private method was called N times instead of
  asserting observable output.

Rewrite to behavior assertions where cheap; otherwise flag. Never delete a failing or
inconvenient test to make a suite green — that is the worst slop of all.

## Severity ordering when time-boxed

1. Defensive slop that swallows errors (actively hides bugs)
2. Duplication of existing utils (divergence over time)
3. Abstraction slop (compounds — others build on it)
4. Dead weight and comment slop (noise, cheap to fix)
5. Naming slop (cheap, but rename-risk on public symbols)
