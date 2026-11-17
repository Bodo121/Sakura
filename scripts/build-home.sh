#!/usr/bin/env bash
set -euo pipefail
source scripts/android-env.sh
SDK_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
SDK_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
KOTLIN_VERSION=2.1.21
KOTLIN_SHA=1ba08a8b45da99339a0601134cc037b54cf85e9bc0edbe76dcbd27c2d684a977
TOOLS_DIR="${SAKURA_TOOLS_DIR:-.local/tools}"
if [[ -n "${KOTLIN_HOME:-}" ]]; then
 KOTLIN_DIR="$KOTLIN_HOME"
else
 KOTLIN_DIR="$TOOLS_DIR/kotlinc"
 if [[ ! -x "$KOTLIN_DIR/bin/kotlinc" ]]; then
  mkdir -p "$TOOLS_DIR"
  curl -fL --retry 3 "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" -o "$TOOLS_DIR/kotlin.zip"
  printf '%s  %s\n' "$KOTLIN_SHA" "$TOOLS_DIR/kotlin.zip" | sha256sum --check
  unzip -q -o "$TOOLS_DIR/kotlin.zip" -d "$TOOLS_DIR"
 fi
fi
work=.local/home-build
mkdir -p "$work/lib" dist/home
# Never update an old APK in place: that could retain stale native libraries or DEX.
rm -rf "$work/classes" "$work/dex" "$work/lib"
mkdir -p "$work/classes" "$work/dex" "$work/lib"
"$KOTLIN_DIR/bin/kotlinc" -jvm-target 1.8 -no-reflect -classpath "$SDK_JAR" -d "$work/classes" platform/android-home/src/dev/sakura/home/*.kt
# Packaging Kotlin stdlib explicitly avoids missing kotlin.* classes at startup.
find "$work/classes" -name '*.class' -print > "$work/class-files.txt"
"$SDK_TOOLS/d8" --lib "$SDK_JAR" --min-api 26 --output "$work/dex" "$KOTLIN_DIR/lib/kotlin-stdlib.jar" @"$work/class-files.txt"
for target in ${SAKURA_HOME_TARGETS:-aarch64-linux-android x86_64-linux-android}; do
 rustup target add "$target"
 case "$target" in
  aarch64-linux-android) abi=arm64-v8a; linker=aarch64-linux-android26-clang; variable=CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER;;
  x86_64-linux-android) abi=x86_64; linker=x86_64-linux-android26-clang; variable=CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER;;
  *) echo "Unsupported Home target: $target" >&2;exit 1;;
 esac
 export "$variable=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/$linker"
 cargo build --locked --release --target "$target" -p sakura-home --lib
 mkdir -p "$work/lib/$abi"
 cp "target/$target/release/libsakura_home.so" "$work/lib/$abi/"
 "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D "$work/lib/$abi/libsakura_home.so" | grep Java_dev_sakura_home_NativeBridge_rank > /dev/null
 "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$work/lib/$abi/libsakura_home.so"
done
"$SDK_TOOLS/aapt2" compile --dir platform/android-home/res -o "$work/resources.zip"
"$SDK_TOOLS/aapt2" link -I "$SDK_JAR" --manifest platform/android-home/AndroidManifest.xml -o "$work/unsigned.apk" "$work/resources.zip"
cp "$work/dex/classes.dex" "$work/classes.dex"
(cd "$work" && zip -q unsigned.apk classes.dex lib/*/*.so)
"$SDK_TOOLS/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
SAKURA_KEYSTORE="${SAKURA_KEYSTORE:-.local/sakura-debug.jks}"
if [[ ! -f "$SAKURA_KEYSTORE" ]]; then
 keytool -genkeypair -keystore "$SAKURA_KEYSTORE" -storepass android -keypass android -alias sakura-debug -dname 'CN=SakuraOS Development' -keyalg RSA -keysize 2048 -validity 10000
fi
output=dist/home/SakuraHome-v0.1.1.apk
"$SDK_TOOLS/apksigner" sign --ks "$SAKURA_KEYSTORE" --ks-key-alias sakura-debug --ks-pass pass:android --key-pass pass:android --out "$output" "$work/aligned.apk"
"$SDK_TOOLS/apksigner" verify --verbose "$output"
"$SDK_TOOLS/aapt2" dump badging "$output"
sha256sum "$output" > dist/home/SHA256SUMS.txt
