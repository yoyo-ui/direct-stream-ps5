# Direct Stream for PS5: native Android app (experimental)

A native Kotlin / Jetpack Compose port of the transfer engine from Direct Stream for PlayStation 5 v2.9.0.
The original Python application in the repository root is **unchanged**. MIT licence and attribution are preserved.

> **Status.** The `:core` module (the whole transfer engine) is compiled and tested on a JVM: 110 tests against mock
> FTP/HTTP servers. The `:app` module (UI, service, persistence) has been written but **has never been compiled or
> run**: the authoring environment had no Android SDK and could not reach Google's Maven repository, so **no APK was
> built**. Expect a few compile errors on first sync.
> Nothing has been tested against a real PS5.

* Android 10+ (API 29), Kotlin 2.0, Compose Material 3, package `com.directstream.ps5`.
* Read `docs/ARCHITECTURE.md` (why a Kotlin engine, lifecycle, security) and `docs/FEATURE_MATRIX.md`
  (what was ported, changed or left out).

## Build
Requires JDK 17 and the Android SDK (API 35). From this folder:
```bash
./gradlew :core:test          # engine tests, JVM only: no Android SDK needed
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug  # → app/build/outputs/apk/debug/app-debug.apk
```
Dependency versions are in `gradle/libs.versions.toml` and were chosen to be compatible but could not be resolved
offline. If sync fails, bump AGP / Kotlin / Compose BOM together.

## Using it
1. **Settings:** enter the PS5's IP, FTP port (default 2121) and credentials if your FTP server needs them, then Save.
2. **Dashboard → Test connection** performs a real connect and login.
3. **Add transfer:** paste a link and tap *Verify link* (the link must pass before it can be queued), or choose a local file.
4. The transfer runs in a foreground service and survives screen lock. Failed or interrupted transfers can be resumed when it is safe.

Your console needs a compatible FTP server (e.g. ftpsrv/GoldHEN FTP). FTP is unencrypted: use a trusted network.

## Limits you should know about
* Parallel download is a speed-up for the *download* side only; the console receives one ordered stream.
* Resume needs the server to support ranges and to send an ETag or Last-Modified header.
* The app never bundles or auto-downloads a console payload. "Extract on console" requires you to import
  `unrar_ps5.elf` yourself and to run a payload loader on the console.
* Not ported: multi-part stitching, remote-ZIP streaming extraction, folder transfer, extraction on the phone.
