# Crash-reporting integration boundary

Status: design and source-inspection record, audited 2026-10-25. The only part
implemented so far is the environment-detection launch signal in
`Environment.kt`. No transport, request queue, URL builder, or report-action
handler exists in this repository.

## Component responsibilities

XtCrash prepares the canonical crash report, constructs destination URLs, and
exposes the report and URL through its public integration contract. XtMapper
receives the prepared report and URL, coordinates the future Android-side host
handoff, keeps request identity and state, and preserves the normal Android
reporting fallback. It must never reconstruct the destination URL. XtLogger
handles XtMapper application logging and existing diagnostics; session metadata
records `Environment.name`. cage-xtmapper (future work, not implemented)
receives the prepared URL, processes the production request/acknowledgment,
exports Linux diagnostics, and hands the URL to the Linux default-browser
mechanism.

## Verified XtCrash API (v1.0.0)

This was audited against the XtCrash repository source. It is published as
`com.github.SoufianoDev:xtcrash` but is not currently a dependency in
`app/build.gradle`.

Exists today:

- `CrashConfig.Builder`: report button/text/subject/chooser title,
  `supportEmail`, `additionalReportInfo`, `errorActivity(Class)`,
  `eventListener`, `logListener`.
- Separate error process `:xtcrash_error`; configuration crosses the process
  boundary only as a `Serializable` intent extra (`CrashConfig` is final, fixed
  field set, no key/value passthrough).
- `CrashActivity.EventListener` (launch/restart/close callbacks) and
  `CrashLogListener` (log forwarding). Neither is a report-action hook.
- `CrashActivity.getAllErrorDetailsFromIntent(...)`: report text only.
- Fallback: share chooser; on `ActivityNotFoundException` the report is copied
  to the clipboard with a toast.

Missing (dependencies for a future XtCrash update; do not reimplement in
XtMapper):

- Configurable reporting destinations (none / email / github / gitlab / custom).
- GitHub/GitLab issue URL construction.
- Prepared-report access (report text and URL exposed to the consumer).
- An optional report-action handler, including class-based construction for
  `:xtcrash_error`.
- Explicit handler result semantics.
- Destination-specific fallback behavior.

## Future report-action handler constraints

Once the XtCrash API exists:

- The handler runs in `:xtcrash_error`, a separate process: it must be
  class-based/serializable configuration, never a live object, lambda, or
  singleton from the original application process.
- Results must distinguish: durably accepted / not handled / definite failure
  before acceptance / uncertain (acceptance may already have occurred). A
  timeout or exception does not prove non-acceptance, and an uncertain handoff
  must never trigger automatic duplicate reporting.
- The handler performs bounded acceptance work only. It never waits for Linux
  diagnostic export, browser navigation, page loading, or remote submission.
- Fallback stays destination-specific: no destination → Android Sharesheet;
  email → email flow; github/gitlab → XtCrash-generated issue URL with its
  specified prefill/manual-paste behavior; custom → developer-configured
  sender. If the Linux handler cannot accept, that normal flow remains. After
  acceptance (or uncertain acceptance), Android sharing is user-triggered only.

## Transport readiness

`DEFERRED: CAGE COMMUNICATION/EXECUTION FEATURE NOT IMPLEMENTED`

Current cage-xtmapper behavior (verified): it launches `RemoteServiceShell`
via `app_process` with `--wayland-client` (plus optional `--xtmapper-args`),
and the only host↔Android channel is the compositor-stdout → process-stdin
pipe. Absent (verified): request queue, request IDs, acknowledgments,
connectivity check, request-path diagnostic export, browser handoff,
`link_delivered`.

Before the request/acknowledgment proof of concept can begin, Cage must
implement:

- Reading and validating a request (planned schema: `schemaVersion`,
  `requestId`, `type`, `message`).
- Writing a correlated acknowledgment.
- Request discovery that does not depend on an unapproved permanent polling
  mechanism in XtMapper.

Required future evidence (none substitutable by unit tests, mocks, or static
review): Android-side request creation; Linux-side request reading and
acknowledgment writing; acknowledgment reading in XtMapper; request-ID
correlation; real permission/access-control observations; request-discovery
behavior; timeout and interruption behavior.

The example payload in the task description is a planned test payload only. It
is not a production schema, and no production request queue is approved by this
note.

## `link_delivered` (future, agreed)

Internal status `link_delivered` means only that the URL was handed off to the
default-browser mechanism according to the available execution evidence. User
message: "Link delivered to the default browser on Linux." It does not imply
that a page loaded, an issue was created, or a report was submitted.
Diagnostic-export status stays independent from browser-handoff status.
