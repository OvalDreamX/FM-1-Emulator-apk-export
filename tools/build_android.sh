#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

command -v cargo >/dev/null || { echo 'Install Rust/Cargo from https://rustup.rs first.' >&2; exit 1; }
command -v cargo-ndk >/dev/null || cargo install cargo-ndk --locked
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || { echo 'Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory.' >&2; exit 1; }

rustup target add aarch64-linux-android
OUT="$ROOT/android/app/src/main/jniLibs"
mkdir -p "$OUT"
cargo ndk -t arm64-v8a -p 26 -o "$OUT" build --release -p fm1-android-native

GRADLE_VERSION=8.13
TOOLS="${XDG_CACHE_HOME:-$HOME/.cache}/fm1-android-build-tools"
GRADLE="$TOOLS/gradle-$GRADLE_VERSION/bin/gradle"
if [[ ! -x "$GRADLE" ]]; then
  mkdir -p "$TOOLS"
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TOOLS/gradle.zip"
  unzip -q -o "$TOOLS/gradle.zip" -d "$TOOLS"
fi
"$GRADLE" -p android assembleRelease --no-daemon
mkdir -p "$ROOT/build"
cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$ROOT/build/FM1-Emulator-Android-arm64.apk"
echo "APK ready: $ROOT/build/FM1-Emulator-Android-arm64.apk"
