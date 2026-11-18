# FM-1 Emulator for Android (experimental)

This is a native Android host for the existing Rust emulator. It does **not** wrap the browser
page or run the WebAssembly build.

## What it contains

- The existing `fm1-core` and `fm1-soc` Rust emulation crates.
- The native AArch64 JIT, with an Android W^X code cache using two mappings of one `memfd`:
  one read/write view for generated instructions and one read/execute view for execution.
- A dedicated Rust emulation thread that fills a bounded single-producer/single-consumer audio ring.
- An Android AAudio low-latency output stream. Its high-priority callback only drains the ring and
  writes silence for any missing frames; it never runs emulated instructions.
- The existing FM-1 and SMK-37 panel renderers, hosted in a landscape Android view with multi-touch.
- `.fwsc` firmware import through Android's document picker, per-firmware flash persistence,
  clock selection, panel themes, and runtime performance reporting.

The original browser app and desktop tools remain in the repository and are not replaced.

## Device target

The APK is built for `arm64-v8a` only. The app uses Android API 26 as its minimum because it uses
AAudio. It is intended for modern ARM64 devices such as the POCO X4 Pro 5G, but performance and
JIT memory permissions must be verified on the actual handset.

## Build on Windows

1. Install Android Studio and its Android SDK components: Android 35 platform, build-tools 35.0.0,
   NDK 28.2.13676358, and CMake 3.22.1.
2. Install a current stable Rust toolchain with `rustup`.
3. Open PowerShell at the repository root and run:

   ```powershell
   .\tools\build_android.ps1
   ```

The script builds the native Rust library, downloads Gradle 8.13 if needed, and creates:

`build\FM1-Emulator-Android-arm64.apk`

## Build on Linux / macOS

Install Rust, the Android SDK/NDK and CMake, set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), then run:

```bash
./tools/build_android.sh
```

## GitHub Actions build

The workflow `.github/workflows/android-apk.yml` builds an ARM64 release APK on pushes affecting
the Android host or when manually dispatched. It uploads the signed APK as the artifact
`FM1-Emulator-Android-arm64`.

## Install and use

1. Install the APK on an ARM64 Android device.
2. Download the `.fwsc` package for the firmware you want to run. Firmware is intentionally not
   bundled in this source project; use firmware you are entitled to use.
3. Open the app, tap **Open .FWSC**, and choose the package from Downloads or another document
   provider.
4. Use the touchscreen panel to play notes, buttons and encoders. Multiple fingers can hold
   multiple keys. Releasing a pointer, cancelling a touch, losing window focus, or backgrounding
the app releases held notes.
5. Firmware flash state is stored privately on the device, separately for each imported package.
   The **Save** control requests a fresh flash snapshot from the emulator thread.

## Performance notes

The audio callback deliberately does not execute the emulator. Emulation runs ahead on its own
worker thread and the audio callback drains a ring buffer. The queue grows by 256 frames after
underruns and gradually shrinks after a stable period. This addresses scheduling jitter, not an
emulator that cannot compute at least one second of emulated time per second of wall time.

The native JIT is attempted once per CPU. If Android denies the executable mapping, the status
panel reports that the JIT is unavailable rather than retrying a large allocation for every hot
block. The app exposes speed, estimated CPU demand, underruns, firmware audio starvation, and JIT
availability so real-device testing can distinguish CPU limits from output-buffer issues.

## Current validation status

This project snapshot has not yet been built with an Android NDK or run on a phone in this
workspace. The build scripts and CI workflow are provided, but a successful compile, installation,
AAudio test, native-JIT confirmation, and real-time playback test are still required before
calling the APK release-ready.
