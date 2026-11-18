#!/usr/bin/env bash
set -euo pipefail
source scripts/android-env.sh
command -v flutter >/dev/null || { echo 'Flutter 3.38.5 is required (see build-home.yml)' >&2; exit 1; }
# Compile the existing Rust search engine for the phone and emulator.
for target in aarch64-linux-android x86_64-linux-android; do
 rustup target add "$target"
 case "$target" in
 aarch64-linux-android) abi=arm64-v8a; linker=aarch64-linux-android26-clang; variable=CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER;;
 x86_64-linux-android) abi=x86_64; linker=x86_64-linux-android26-clang; variable=CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER;;
 esac
 export "$variable=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/$linker"
 cargo build --release --locked --target "$target" -p sakura-home --lib
 mkdir -p "platform/flutter-home/android/app/src/main/jniLibs/$abi"
 cp "target/$target/release/libsakura_home.so" "platform/flutter-home/android/app/src/main/jniLibs/$abi/"
done
# Generate only standard Gradle wrapper binaries from the pinned Flutter SDK.
bootstrap="${RUNNER_TEMP:-$PWD/.local}/sakura-flutter-bootstrap"
if [[ ! -f platform/flutter-home/android/gradlew ]]; then
 flutter create --platforms android --project-name sakura_bootstrap --org dev.sakura --no-pub "$bootstrap"
 cp -R "$bootstrap/android/gradle" platform/flutter-home/android/
 cp "$bootstrap/android/gradlew" platform/flutter-home/android/
fi
(cd platform/flutter-home && flutter pub get && flutter analyze && flutter test && flutter build apk --release --target-platform android-arm64,android-x64)
mkdir -p dist/home .local
SAKURA_KEYSTORE="${SAKURA_KEYSTORE:-.local/sakura-debug.jks}"
if [[ ! -f "$SAKURA_KEYSTORE" ]]; then
 keytool -genkeypair -keystore "$SAKURA_KEYSTORE" -storepass android -keypass android -alias sakura-debug -dname 'CN=SakuraOS Development' -keyalg RSA -keysize 2048 -validity 10000
fi
SDK_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
"$SDK_TOOLS/apksigner" sign --ks "$SAKURA_KEYSTORE" --ks-key-alias sakura-debug --ks-pass pass:android --key-pass pass:android --out dist/home/SakuraHome-v0.1.3.apk platform/flutter-home/build/app/outputs/flutter-apk/app-release.apk
"$SDK_TOOLS/apksigner" verify --verbose dist/home/SakuraHome-v0.1.3.apk
"$SDK_TOOLS/aapt2" dump badging dist/home/SakuraHome-v0.1.3.apk
sha256sum dist/home/SakuraHome-v0.1.3.apk > dist/home/SHA256SUMS.txt
