# Sakura Home 0.1.1 — Kotlin + Rust

The launcher has been rebuilt as a regular Kotlin Android Activity with programmatic Android Views. Rust supplies a portable ranked-search function through JNI. The old Slint NativeActivity entry point is no longer used by Sakura Home. This change follows the revised launcher direction; the other five apps remain their earlier Slint prototypes and are not repaired by this APK.

## What works in the implementation

- Discovers the current Android user's exported launcher activities using a scoped MAIN/LAUNCHER package-visibility query; no QUERY_ALL_PACKAGES permission.
- Opens real installed applications and system app-information/uninstall confirmation screens.
- Home favorites, pages, four-slot dock, and long-press pin/unpin menus. Order persists in private SharedPreferences; pinning to a full dock replaces its oldest entry.
- Swipe up opens a recycled GridView app library; horizontal home swipes switch pages; swipe down or + opens customization.
- Unicode-aware search in Rust, with a Kotlin fallback if loading/calling the native library fails. A native-loading failure does not finish the Activity.
- Live clock and date, updated once per minute only while resumed; the clock opens alarms where available. Connections opens Android wireless settings. No invented weather or unread counts.
- Four original gradient palettes, an AMOLED palette, photo wallpaper import through Android's document picker, 4/5 columns, 48/56/64 dp icons, and optional home labels. The wallpaper applies inside Sakura Home only.
- An explicit standard Android HOME role request from customization. The app never automatically selects itself as the default launcher.
- No APatch, root, daemon, boot changes, internet permission or storage-wide access required.

## Install and use

Install `SakuraHome-v0.1.1.apk`. Its package is still `dev.sakura.home`, with versionCode 101. A build signed by the same development identity upgrades the previous APK. If Android reports a conflicting signing certificate (for example, a separate CI build generated another key), uninstall the earlier **Sakura Home app only**, then install this APK. The APatch module is unrelated.

Tap + to customize; long-press an app to add/remove favorites or dock entries. Swipe up for all apps; tap Search to open the keyboard. Default launcher selection is optional and manual. Android Settings → Apps → Default apps → Home lets you return to your previous launcher.

## Builds and tests

Run `bash scripts/build-home.sh` with ANDROID_HOME set (SDK platform 34, build-tools 35.0.0, NDK 27.2.12479018). The script downloads the pinned Kotlin 2.1.21 compiler and verifies its SHA-256, includes Kotlin stdlib in DEX, builds ARM64 and x86-64 JNI libraries, aligns/signs the APK, and checks signatures. It reuses `.local/sakura-debug.jks` or the SAKURA_KEYSTORE override. Signing credentials are excluded from source and output archives. KOTLIN_HOME can select an existing compiler.

`cargo test -p sakura-home` covers ranking, Unicode, ordering and token matching. `build-home.yml` builds and installs the real APK on a disposable Android 14 x86-64 emulator, launches HomeActivity, exercises discovery/JNI/search/drawer, tests HOME selection and relaunch, collects screenshots and crash logs. Its result must be inspected before claiming runtime validation. The optional SAKURA_DEBUG_KEYSTORE_BASE64 repository secret preserves CI signing identity.

## Limits

This is a launcher developer build, not a standalone OS. External app widgets, user-created folders, drag-and-drop layout editing, notification badges, work/private profiles and icon packs are not implemented. Android View/Canvas rendering replaces the previous native-rendering path for this launcher. Surface blur is represented by translucent cards, not advertised as real-time background blur. Device-specific gesture navigation, OEM animation integration, battery use, 120 Hz frame timing and physical Xiaomi behavior require testing on the actual phone.
