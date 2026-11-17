#!/usr/bin/env bash
set -euo pipefail
source scripts/android-env.sh
rustup target add aarch64-linux-android
cargo build --locked --release --target aarch64-linux-android -p sakura -p sakurad
mkdir -p dist/arm64
cp target/aarch64-linux-android/release/{sakura,sakurad} dist/arm64/
for binary in dist/arm64/*; do
 "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q AArch64
 "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$binary"
done
