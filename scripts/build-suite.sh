#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/gsi-apps .local
bootstrap="${RUNNER_TEMP:-$PWD/.local}/sakura-flutter-bootstrap"
if [[ ! -f platform/flutter-suite/android/gradlew ]]; then
 if [[ ! -f "$bootstrap/android/gradlew" ]]; then flutter create --platforms android --project-name sakura_bootstrap --org dev.sakura --no-pub "$bootstrap";fi
 cp -R "$bootstrap/android/gradle" platform/flutter-suite/android/
 cp "$bootstrap/android/gradlew" platform/flutter-suite/android/
fi
(cd platform/flutter-suite && flutter pub get --enforce-lockfile && flutter analyze)
SAKURA_KEYSTORE="${SAKURA_KEYSTORE:-.local/sakura-debug.jks}"
test -f "$SAKURA_KEYSTORE"
SDK_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
for entry in settings:Settings files:Files phone:Phone messages:Messages contacts:Contacts gallery:Gallery camera:Camera clock:Clock calculator:Calculator calendar:Calendar music:Music recorder:Recorder notes:Notes installer:Installer wallpapers:Wallpapers updater:Updater terminal:Terminal coremanager:CoreManager patch:Patch; do
 kind="${entry%:*}";label="${entry#*:}"
 (cd platform/flutter-suite && flutter build apk --release --flavor "$kind" --dart-define "SAKURA_APP=$kind" --target-platform android-arm64)
 "$SDK_TOOLS/apksigner" sign --ks "$SAKURA_KEYSTORE" --ks-key-alias sakura-debug --ks-pass pass:android --key-pass pass:android --out "dist/gsi-apps/Sakura$label.apk" "platform/flutter-suite/build/app/outputs/flutter-apk/app-$kind-release.apk"
 "$SDK_TOOLS/apksigner" verify --verbose "dist/gsi-apps/Sakura$label.apk"
 "$SDK_TOOLS/aapt2" dump badging "dist/gsi-apps/Sakura$label.apk"
done
cp dist/home/SakuraHome-v0.2.0.apk dist/gsi-apps/SakuraHome.apk
sha256sum dist/gsi-apps/*.apk > dist/gsi-apps/SHA256SUMS.txt
