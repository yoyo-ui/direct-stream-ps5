# v2.9.0 feature matrix

Legend: **Ported** = re-implemented in Kotlin and tested on a JVM. **Ported, untested on hardware** = same, but only
a mock console/host was available. **UI only** = code is written but the Android module was never compiled or run.
**Not ported** = not present in the Android app.

| # | v2.9.0 capability | Status | Notes |
|---|---|---|---|
| 1 | Direct HTTP/HTTPS → PS5 FTP streaming | Ported | Bounded RAM, no disk staging. Tested end to end against mock servers. |
| 2 | Preflight: redirects, filename, size | Ported | Includes the original's HTML-page rejection and Content-Encoding check. |
| 3 | Provider resolution | Ported (partly) | PixelDrain, Google Drive, Internet Archive, MediaFire, GoFile, 1fichier, BuzzHeavier/Send.cm and generic heuristics, using the original's own test fixtures. **Not ported:** other site handlers in `resolver.py` (Rootz, AkiraBox, DataNodes, VikingFile, FileDitch, …). HTML scraping is fragile and was never run against live sites. |
| 4 | Parallel range downloads | Ported, changed | Ordered single-writer pipeline (see ARCHITECTURE.md). |
| 5 | Bounded RAM + backpressure | Ported | Tested: a fast source cannot run ahead of a throttled console. |
| 6 | Progress, cancel, retry, recovery | Ported | Session-level reconnect + REST resume; pause/cancel (keep or delete partial). |
| 7 | `.ps5part` partials, rename on finish | Ported | Rename only after size (or checksum) verification. |
| 8 | Resume validation | Ported | ETag/Last-Modified/length for links; identity fingerprint for local files. |
| 9 | Overwrite protection | Ported | Default off; confirmation dialog to enable. |
| 10 | Console file browser | Ported + UI only | list/mkdir/rename/delete/upload. |
| 11 | Smart destination routing | Ported | Same defaults; editable. |
| 12 | Archive strategy selection | Ported (2 of 3) | *Send as is* works. *Extract on console* works only with a user-imported payload. *Extract on this device* is **not available** (the original shells out to `unar`/`7z`, which Android lacks). |
| 13 | Console-side extraction | Ported, untested on hardware | Same flow: archive → `/data/unrar`, `config.ini` (byte-identical to the Python output), payload to port 9021, poll `unrar.log`. Verified only against a mock console. The post-extraction staging "normalise" step is not ported. |
| 14 | Presets and diagnostics | Ported | Real tests/benchmarks; recommendations derive only from measured numbers. |
| 15 | Sanitised error report + GitHub issue link | Ported | |
| 16 | Existing test coverage | Partly | 100 Python tests were run before any change (97 pass, 3 environment errors, 1 skipped). The Kotlin suite re-creates the behaviours above (110 tests); it is not a line-for-line port of the Python suite. |
| – | Multi-part stitching (`.001` → one file) | **Not ported** | Detection only. |
| – | Remote-ZIP streaming extraction (`zip_streamer.py`) | **Not ported** | |
| – | Local folder transfer | **Not ported** | |
| – | Web dashboard, Termux scripts | Untouched | Original application is unchanged. |
