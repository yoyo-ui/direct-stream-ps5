# Architecture decision: native Kotlin engine (Approach B)

## Question
Can the Python engine (`transfer_core.py`, ~7,400 lines with `resolver.py`, `zip_streamer.py`, `ps5_streamer.py`) run
inside a native Android app (Approach A), or must it be ported to Kotlin (Approach B)?

## What was checked
| Concern | Finding |
|---|---|
| Runtime | Android has no system Python. Approach A needs an embedded interpreter (e.g. Chaquopy) plus a Gradle plugin and a Python runtime packaged per ABI. This could not be resolved or tested in the authoring sandbox (no access to Google/Maven repositories). |
| Shelling out | `transfer_core.py` calls external programs (`unar`, `7z`) for staged archive extraction. 3 of the 100 baseline tests fail on a machine without them. Those binaries do not exist on Android. |
| Lifecycle | The engine is long-lived blocking I/O on threads. Android may kill the process at any time and limits foreground services (Android 15: ~6 h for `dataSync`). A Python runtime inside the process would be killed with it; state must be persisted outside Python either way. |
| Stdlib networking | The engine uses `http.client`/`ftplib`. These work in an embedded interpreter but would need their own TLS/DNS/permission handling and would hide errors behind an IPC layer (loopback HTTP) that must itself be secured. |
| Size/startup | Embedding CPython adds tens of MB per ABI and a cold-start cost. |

## Decision
**Approach B.** The transfer engine is re-implemented in Kotlin as a pure-JVM module (`:core`) with no Android
dependencies, so it is unit- and integration-tested on a normal JVM against mock FTP and HTTP servers. The Android
module (`:app`) only adds UI, persistence, the foreground service and Android-specific I/O (Storage Access Framework).

Behaviour was ported from the Python source, not guessed: header set, probe logic, resume rules, `.ps5part`
naming, `config.ini` format, unrar log parsing and the resolver patterns were read from v2.9.0. Where it was
cheap to prove equivalence it is tested against the original's own outputs/fixtures (see FEATURE_MATRIX.md).

## Deliberate differences from the original
* **Parallel downloads feed one ordered FTP writer.** Chunks are fetched concurrently into a bounded window, then
  written strictly in order. No concurrent or offset writes to one remote file.
* **Memory is bounded globally** (`MemoryBudget`), including the parallel window; the engine falls back to a single
  stream when the budget can't hold two chunks.
* **Private/loopback/CGNAT targets are refused** for links (opt-in for a LAN NAS), and redirects may never move
  from a public host to a private one or from https to http. The original only checks the scheme.
* **Credentials are not forwarded across hosts** on redirect; secrets are redacted from every log and error.
* **No guessing of archive passwords from site names.** Only an explicit `password=...` in a name is used.
* **The extractor payload is never bundled.** The user imports it; it is sent only to the configured console
  host, only after a per-transfer confirmation, and only if it is an ELF image.
* Resume across runs requires a strong ETag or Last-Modified (same rule as the original); within one run a
  reconnect only needs ranges.

## Modules
```
core/   pure JVM, tested here        app/   Android, NOT built or run here
  util      redaction, path safety     data       Keystore store, settings, SQLite job store
  net       URL policy                 transfer   TransferManager, foreground service, SAF sources
  http      preflight, ranges          ui         Compose screens (dashboard, transfers, console files,
  ftp       client, parsers                       diagnostics, settings)
  engine    pipeline, resume, verify
  resolver  host link resolution
  console   "Extract on PS5" protocol
  diag      diagnostics, presets, validation, error receipt
```

## Lifecycle
* Every state change is written to SQLite (metadata only; secrets encrypted with an Android Keystore key).
* After process death `JobRecovery.onProcessRestart` marks interrupted jobs *paused-resumable*,
  *paused-must-restart* (with the reason) or *failed-source-lost*; nothing resumes silently with unsafe assumptions.
* A foreground service (not WorkManager) keeps a multi-hour stream alive, holds Wi-Fi/CPU locks only while work
  exists, handles the Android 15 `dataSync` timeout by pausing, and stops itself when idle.
* Network loss during a transfer moves a recoverable job to *waiting for network* and resumes it when a network returns.
