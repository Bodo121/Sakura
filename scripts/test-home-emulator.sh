#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
mkdir -p dist/home/runtime-tests
SAKURA_ANDROID_USER_DIR="${RUNNER_TEMP:-$PWD/.local}/sakura-android-user"
export ANDROID_USER_HOME="$SAKURA_ANDROID_USER_DIR"
export ANDROID_EMULATOR_HOME="$SAKURA_ANDROID_USER_DIR"
export ANDROID_AVD_HOME="$SAKURA_ANDROID_USER_DIR/avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | avdmanager create avd --force --name SakuraHomeTest --path "$ANDROID_AVD_HOME/SakuraHomeTest.avd" --package 'system-images;android-34;google_apis;x86_64' --device pixel_7
# Explicit metadata keeps old command-line tools and newer emulator versions in agreement.
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-34\n' "$ANDROID_AVD_HOME/SakuraHomeTest.avd" > "$ANDROID_AVD_HOME/SakuraHomeTest.ini"
test -f "$ANDROID_AVD_HOME/SakuraHomeTest.avd/config.ini"
emulator -list-avds
if [[ -e /dev/kvm ]]; then sudo chmod a+rw /dev/kvm; fi
emulator -avd SakuraHomeTest -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -memory 2048 > dist/home/runtime-tests/emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
 local result=$?
 set +e
 adb logcat -b crash -d > dist/home/runtime-tests/crashes.txt
 adb logcat -d -s SakuraHome AndroidRuntime > dist/home/runtime-tests/sakura.log
 for image in home search library; do
  adb pull "/sdcard/Android/data/dev.sakura.home/files/$image.png" "dist/home/runtime-tests/$image.png" >/dev/null 2>&1
 done
 adb emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null
 exit "$result"
}
trap cleanup EXIT
adb start-server
transport_ready=0
for attempt in $(seq 1 120); do
 if adb devices | grep -E '^emulator-[0-9]+[[:space:]]+device$' > /dev/null; then transport_ready=1;break;fi
 if ! kill -0 "$emulator_pid" 2>/dev/null; then tail -n 100 dist/home/runtime-tests/emulator.log;exit 1;fi
 sleep 2
done
if [[ "$transport_ready" != 1 ]]; then tail -n 100 dist/home/runtime-tests/emulator.log;exit 1;fi
ready=0
for attempt in $(seq 1 180); do
 if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then ready=1;break;fi
 if ! kill -0 "$emulator_pid" 2>/dev/null; then tail -n 100 dist/home/runtime-tests/emulator.log;exit 1;fi
 sleep 2
done
[[ "$ready" == 1 ]]
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
adb shell input keyevent 82
original_home=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)
adb install -r dist/home/SakuraHome-v0.1.1.apk
adb logcat -c
adb shell am instrument -w dev.sakura.home/.SmokeInstrumentation | tee dist/home/runtime-tests/instrumentation.txt
grep 'Sakura Home smoke tests passed' dist/home/runtime-tests/instrumentation.txt > /dev/null
adb pull /sdcard/Android/data/dev.sakura.home/files/home.png dist/home/runtime-tests/home.png
adb pull /sdcard/Android/data/dev.sakura.home/files/search.png dist/home/runtime-tests/search.png
adb pull /sdcard/Android/data/dev.sakura.home/files/library.png dist/home/runtime-tests/library.png
# Default launcher selection is exercised on this disposable emulator only.
adb shell cmd package set-home-activity dev.sakura.home/.HomeActivity
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell pidof dev.sakura.home
adb shell dumpsys activity activities > dist/home/runtime-tests/activities.txt
adb logcat -b crash -d > dist/home/runtime-tests/crashes.txt
if grep -E 'Process: dev.sakura.home|>>> dev.sakura.home <<<' dist/home/runtime-tests/crashes.txt; then exit 1;fi
if [[ "$original_home" == */* && "$original_home" != dev.sakura.home/* ]]; then
 adb shell cmd package set-home-activity "$original_home"
 adb shell input keyevent KEYCODE_HOME
fi
adb shell am force-stop dev.sakura.home
adb shell am start -W -n dev.sakura.home/.HomeActivity | tee dist/home/runtime-tests/cold-start.txt
sleep 2
adb shell pidof dev.sakura.home
adb logcat -d -s SakuraHome > dist/home/runtime-tests/sakura.log
