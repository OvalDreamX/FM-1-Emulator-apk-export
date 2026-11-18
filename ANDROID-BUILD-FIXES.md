# FM-1 Android package update

This is a full source-project snapshot based on the supplied `fm1-android-project.zip`, with the build fixes identified from GitHub Actions runs.

## Applied changes

- `crates/fm1-android-native/src/lib.rs`: imports `fm1_core::Bus`, required for the `Soc::write32` trait method.
- `android/app/src/main/cpp/AudioOutput.cpp`: removes the explicit AAudio usage/content-type setters, which are API 28+ only. AAudio defaults already select media usage and music content type, retaining compatibility with the app's API 26 minimum.
- `.github/workflows/android-apk.yml`: updated to match the current GitHub Actions workflow, including aligned NDK environment variables and the corrected `cargo ndk -P 26` API-level option.
- `tools/build_android.ps1` and `tools/build_android.sh`: corrected the same `cargo-ndk` API-level flag for local builds.

## Build status

This package has not itself been run through a successful end-to-end Android build. The latest GitHub Actions evidence available when this package was assembled showed Rust native compilation passing, with the C++ build failing on the two API-28-only AAudio setters. Those are removed here. Gradle packaging and installation on a physical phone remain to be verified.

The workflow is debug-signed for sideload testing; it is not configured with a private production signing key.
