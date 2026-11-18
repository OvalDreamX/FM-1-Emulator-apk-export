$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Rust/Cargo is required. Install rustup from https://rustup.rs and reopen PowerShell.'
}
if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    Write-Host 'Installing cargo-ndk…'
    cargo install cargo-ndk --locked
    if ($LASTEXITCODE -ne 0) { throw 'cargo-ndk installation failed.' }
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK directory (Android Studio installs it).'
}

if (Get-Command rustup -ErrorAction SilentlyContinue) { rustup target add aarch64-linux-android }

$NdkOutput = Join-Path $Root 'android/app/src/main/jniLibs'
New-Item -ItemType Directory -Force -Path $NdkOutput | Out-Null
Write-Host 'Building the native ARM64 emulator…'
cargo ndk -t arm64-v8a -P 26 -o $NdkOutput build --release -p fm1-android-native
if ($LASTEXITCODE -ne 0) { throw 'Rust/Android native build failed.' }

$GradleVersion = '8.13'
$ToolsDir = Join-Path $env:LOCALAPPDATA 'FM1AndroidBuildTools'
$GradleHome = Join-Path $ToolsDir "gradle-$GradleVersion"
$GradleExe = Join-Path $GradleHome 'bin/gradle.bat'
if (-not (Test-Path $GradleExe)) {
    New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
    $Zip = Join-Path $ToolsDir "gradle-$GradleVersion-bin.zip"
    $Url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
    Write-Host "Downloading Gradle $GradleVersion…"
    Invoke-WebRequest -Uri $Url -OutFile $Zip
    Expand-Archive -Path $Zip -DestinationPath $ToolsDir -Force
}

Write-Host 'Building the Android APK…'
& $GradleExe -p android assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Gradle APK build failed.' }

$Apk = Join-Path $Root 'android/app/build/outputs/apk/release/app-release.apk'
$OutputDir = Join-Path $Root 'build'
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$OutputApk = Join-Path $OutputDir 'FM1-Emulator-Android-arm64.apk'
Copy-Item $Apk $OutputApk -Force
Write-Host "APK ready: $OutputApk"
