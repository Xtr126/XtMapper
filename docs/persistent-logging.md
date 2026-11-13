Persistent logs: daily log files are written to the fixed folder `XtMapper/Logs`
on shared storage (`XtMapper-Debug/Logs` for debug builds). They are user-visible
and are enabled from the main screen by allowing All-files access (Android 11+).
The current production logger writes each process role (app, shell, shizuku, root) to its own
`xtmapper[-role]-YYYY-MM-DD.log` file, wrapped in `SESSION START`/`SESSION END`
markers, with bounded size, rotation and retention.

An opt-in coordinator protocol implements one shared `xtmapper-YYYY-MM-DD.log`
per date, with a shared daily byte cap, no same-day rollover, and receipt-backed
legacy migration. It is not enabled automatically in production. Activation and
merge acceptance still require cross-UID Android exclusion/descriptor-close probes,
physical-file telemetry/lifecycle/END evidence, and shell-server restart checks.