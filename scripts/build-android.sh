#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (C) 2026 SecretArrow.
#
# Local helper that builds the Rust crate for Android ABIs and assembles
# the release APK. CI does the same via .github/workflows/build-android.yml
# — this script is for developer convenience only.
#
# Prerequisites:
#   - Android SDK with NDK r26d and CMake 3.22.1
#   - Rust toolchain with cargo-ndk
#   - ANDROID_HOME / ANDROID_NDK_HOME env vars set
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Adding Android Rust targets"
rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi

echo "==> Installing cargo-ndk (if missing)"
cargo install cargo-ndk --locked 2>/dev/null || true

echo "==> Building rust/noidmobile-pow for Android ABIs"
cd rust/noidmobile-pow
cargo ndk -t arm64-v8a -t x86_64 -t armeabi-v7a \
    -o ../../android/app/src/main/jniLibs build --release

echo "==> Built shared libraries:"
ls -la ../../android/app/src/main/jniLibs/*/

echo "==> Assembling release APK"
cd ../../android
./gradlew assembleRelease --no-daemon

echo "==> Done. APK at app/build/outputs/apk/release/"
ls -la app/build/outputs/apk/release/
