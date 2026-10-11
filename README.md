# Direct Stream PS5 (Android fork)

A fork of [ChillQuant/direct-stream-ps5](https://github.com/ChillQuant/direct-stream-ps5) that adds a **native Android app**, built automatically with GitHub Actions.

Direct Stream streams HTTP/HTTPS downloads straight into your PlayStation 5's storage over your local network via FTP, without saving the file to your phone or computer first.

> This project is independent and is not affiliated with, endorsed by, or sponsored by Sony Interactive Entertainment. PlayStation and PS5 are trademarks of their respective owners.

---

## What's in this fork

- **Native Android app** in the [`android/`](android) folder, split into an `app` module (the Android UI) and a `core` module (the transfer engine, covered by JVM unit tests).
- **GitHub Actions build** that runs the engine tests and produces a debug APK you can download from the workflow run, so you don't need Android Studio.
- Everything from the original project (the Python streamer for macOS, Windows, Linux and Termux) is still here.

## What the original project does

- Streams a direct download link into PS5 FTP storage with no local disk staging.
- Parallel HTTP range workers (1 to 32 streams) with a RAM ring buffer.
- Handles ZIP, RAR and 7Z archives, multi-part archives and split PKG files.
- Resumes interrupted transfers and checks file integrity before finalizing.
- Remote file browser for PS5 storage, and smart routing by file type.
- Runs locally only, using per-session tokens.

See the [upstream README](https://github.com/ChillQuant/direct-stream-ps5#readme) for full documentation of these features.

## Requirements

- A PS5 running an FTP server app (for example ftps5 or GoldHEN FTP), on the same network as your device.
- A **direct** download link to the file (a link to the file itself, not a web page that contains it).

## Get the Android APK

1. Go to the **Actions** tab of this repository.
2. Open the latest successful **Build Android APK** run.
3. Download the `direct-stream-ps5-debug-apk` artifact, unzip it, and install the `.apk` on your phone (you'll need to allow installs from unknown sources).

You can also trigger a fresh build yourself: **Actions → Build Android APK → Run workflow**.

## Build it yourself

You need JDK 17 and the Android SDK.

```bash
cd android
chmod +x gradlew
./gradlew :core:test          # run the engine tests
./gradlew :app:assembleDebug  # build the debug APK
```

The APK is written to `android/app/build/outputs/apk/debug/`.

## Run the original Python version

```bash
git clone https://github.com/yoyo-ui/direct-stream-ps5.git
cd direct-stream-ps5
python3 ps5_streamer.py
```

Use `--host 0.0.0.0` if you want to reach it from another device on your network. Requires Python 3.9 or newer.

#Intended Use

Direct Stream PS5 is a general-purpose file transfer utility. It does not provide copyrighted games, license keys, or PlayStation system software. Users must ensure they have the legal right to access, download, and transfer any content they use with the tool.

This project is independently developed and is not affiliated with or endorsed by Sony Interactive Entertainment. This notice does not imply authorization by Sony or override any applicable laws or platform terms.
## Contact

You can open an [issue](https://github.com/yoyo-ui/direct-stream-ps5/issues) on this repository.

## Credits

- Original project and Python streamer by [ChillQuant](https://github.com/ChillQuant/direct-stream-ps5).
- Android port and CI setup by [yoyo-ui](https://github.com/yoyo-ui) (Discord: @youssefdev).

## Legal

Use this tool only with content you own or are licensed to use, and only with consoles you own. You are responsible for complying with the laws and terms of service that apply to you. The software is provided as is, without warranty of any kind.

## License

Released under the MIT License, the same as the original project. Keep the original `LICENSE` file and copyright notice in this repository.
