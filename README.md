# Direct Stream PS5 (Android fork)

A fork of [ChillQuant/direct-stream-ps5](https://github.com/ChillQuant/direct-stream-ps5) that adds a **native Android app**, built automatically with GitHub Actions.

Direct Stream streams HTTP/HTTPS downloads straight into your PlayStation 5's storage over your local network via FTP, without saving the file to your phone or computer first.

> **Independent project notice:** This project is independently developed and maintained. It is not affiliated with, authorized by, endorsed by, or sponsored by Sony Interactive Entertainment. PlayStation and PS5 are trademarks of their respective owners. All third-party names and marks are used solely to identify the relevant products or services.

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

## Intended Use

Direct Stream PS5 is a file transfer utility. It does not itself provide copyrighted games, license keys, or PlayStation system software. Users are responsible for ensuring that they have the necessary rights and permissions to access, download, store, and transfer any content used with the software.

Users must comply with applicable laws, intellectual property rights, contractual obligations, and the terms applicable to their devices, accounts, and services. The availability of a technical feature does not establish that a particular use of that feature is authorized or lawful.

This project is not intended to encourage copyright infringement, unauthorized access, or circumvention of technological protection measures. No representation is made that every possible use of the software is permitted by applicable law or by any third-party platform's terms.

## Contact

You can open an [issue](https://github.com/yoyo-ui/direct-stream-ps5/issues) on this repository.

## Credits

- Original project and Python streamer by [ChillQuant](https://github.com/ChillQuant/direct-stream-ps5).
- Android port and CI setup by [yoyo-ui](https://github.com/yoyo-ui)


The original author's work is credited here to acknowledge its origin. This attribution does not imply that the original author endorses this fork or is responsible for its modifications.

## Legal Disclaimer

This software is provided "AS IS", without warranties or conditions of any kind, to the maximum extent permitted by applicable law. The authors and contributors make no guarantees regarding the software's reliability, availability, fitness for a particular purpose, or suitability for any particular use.

To the maximum extent permitted by applicable law, the authors and contributors shall not be liable for claims, damages, or other liabilities arising from the use of, inability to use, modification of, or distribution of the software. Nothing in this notice excludes or limits liability where such exclusion or limitation is prohibited by law.

Users are responsible for their own use of the software and for determining whether that use complies with applicable law and third-party terms. This notice does not grant permission to use third-party intellectual property, bypass access controls, or distribute content without the necessary authorization.

Nothing in this README constitutes a statement that Sony Interactive Entertainment or any other third party has reviewed, approved, certified, or authorized this project.

## License

This project is distributed under the MIT License, subject to the terms of that license and any applicable licenses governing third-party components.

The original `LICENSE` file and applicable copyright notices must be retained. Modifications and additions in this fork do not transfer ownership of the original project's code or grant rights to third-party software, trademarks, content, or other materials.

Before redistributing this project or its packaged artifacts, verify that the relevant source files, dependencies, binaries, and other included materials may be redistributed under their applicable licenses.
