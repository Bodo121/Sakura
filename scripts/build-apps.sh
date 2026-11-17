#!/usr/bin/env bash
set -euo pipefail
source scripts/android-env.sh
SDK_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
SDK_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
mkdir -p .local/android/classes .local/android/dex dist/apps
java --module jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -classpath "$SDK_JAR" -d .local/android/classes platform/android-host/SakuraActivity.java
"$SDK_TOOLS/d8" --lib "$SDK_JAR" --min-api 34 --output .local/android/dex .local/android/classes/dev/sakura/host/*.class
# A persistent local key is generated only once; CI can inject a protected signing key.
SAKURA_KEYSTORE="${SAKURA_KEYSTORE:-.local/sakura-debug.jks}"
if [[ ! -f "$SAKURA_KEYSTORE" ]]; then
 keytool -genkeypair -keystore "$SAKURA_KEYSTORE" -storepass android -keypass android -alias sakura-debug -dname 'CN=SakuraOS Development' -keyalg RSA -keysize 2048 -validity 10000
fi
cargo build --locked --release --target aarch64-linux-android -p sakura
for app in settings files core-manager patch terminal; do
 crate="sakura-$app"; [[ "$app" != patch ]] || crate=sakura-patch-app
 cargo build --locked --release --target aarch64-linux-android -p "$crate" --lib
 native="${crate//-/_}"; package="${app//-/}"
 case "$app" in
  settings) label=SakuraSettings;; home) label=SakuraHome;; files) label=SakuraFiles;;
  core-manager) label=SakuraCoreManager;; patch) label=SakuraPatch;; terminal) label=SakuraTerminal;;
 esac
 work=".local/android/$app"; mkdir -p "$work/lib/arm64-v8a"
 cp "target/aarch64-linux-android/release/lib$native.so" "$work/lib/arm64-v8a/"
 cp target/aarch64-linux-android/release/sakura "$work/lib/arm64-v8a/libsakura_cli.so"
 cp .local/android/dex/classes.dex "$work/"
 python3 scripts/make-manifest.py "$work/AndroidManifest.xml" "$package" "$label" "$native"
 "$SDK_TOOLS/aapt2" link -I "$SDK_JAR" --manifest "$work/AndroidManifest.xml" -o "$work/unsigned.apk"
 (cd "$work" && zip -q -u unsigned.apk classes.dex lib/arm64-v8a/*.so)
 "$SDK_TOOLS/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
 "$SDK_TOOLS/apksigner" sign --ks "$SAKURA_KEYSTORE" --ks-key-alias sakura-debug --ks-pass pass:android --key-pass pass:android --out "dist/apps/$label.apk" "$work/aligned.apk"
 "$SDK_TOOLS/apksigner" verify --verbose "dist/apps/$label.apk"
 "$SDK_TOOLS/aapt2" dump badging "dist/apps/$label.apk"
done
bash scripts/build-home.sh
cp dist/home/SakuraHome-v0.1.1.apk dist/apps/SakuraHome.apk
(cd dist/apps && zip -q -j ../SakuraOS-Apps-v0.1.0.zip ./*.apk)
