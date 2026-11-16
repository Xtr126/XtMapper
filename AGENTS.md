# Working on XtMapper

XtMapper maps keyboard and mouse input to Android touch. Application code lives in
`app/src/main/java/xtr/keymapper/`, Binder interfaces in `app/src/main/aidl/`,
and native input code in `app/src/main/cpp/` (built by `app/Android.mk`).

- Keep changes focused and preserve unrelated edits. Put user-facing UI and toast
  strings in resources.
- Preserve stored mappings and metadata when toggling a profile. Shell startup
  selects Default independently of the last selected profile.
- Keep mouse connection state on the main handler. Discovery, a successful device
  open, and delivered events are distinct. Preserve native descriptor/thread
  ownership and service teardown.
- Use [development setup](docs/development.md) and [validation](docs/validation.md).
  Report what ran, what passed, and what still needs device verification.
- Open and future PRs need instrumentation tests when the changed behavior
  depends on Android runtime integration. A human must review generated code
  and its validation before merge.

For startup or input issues, follow [service debugging](docs/service-debugging.md)
and capture both app and server logs. For editor changes, read the
[editor save lifecycle](docs/editor-lifecycle.md).
