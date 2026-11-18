# Sakura Home 0.1.3 — Flutter migration

The launcher UI is now Flutter Material 3, with native Kotlin Android integration and the existing Rust JNI search engine. No APatch module, root, internet permission or broad filesystem access is needed. The five other preview apps are unchanged.

The package remains `dev.sakura.home` (version code 103). Existing `sakura_home` Android preferences and the app-private `wallpaper.jpg` are read directly. An update preserves these when signed with the same key; Android rejects an update signed with a different key. Do not uninstall a working installation before backing up its settings and wallpaper if they matter.

## Rendering and motion

Material 3 themes, segmented controls, switches, bottom sheets, custom Sakura mark, rounded app icons, touch scale feedback, drag-driven library and critically damped spring settling. The library grid creates visible items lazily; icon requests are cached and catalog IO runs on one worker. The clock timer stops when backgrounded. Motion observes the system reduce-animation setting.

The dock actually instantiates QWEA0's `LiquidGlassView`, pinned to **v2.0.11**, commit `fc6bd34fa3290638c3594cc39386f549d1316ab5`. Its Kotlin/C++/AGSL sources and MIT license are vendored under `platform/flutter-home/android/liquidglass`. Only its Gradle build configuration is adapted to the host. The native view samples a coordinate-aligned copy of the launcher wallpaper beneath the dock; it does not attempt to capture Flutter's SurfaceView. Icons and gestures remain Flutter widgets above the glass. Sensor highlights and continuous background captures are disabled for this static wallpaper surface. The library drawer uses Flutter backdrop blur. This distinction avoids claiming full-screen native refraction where it is not implemented.

The linked awesome-ios-animation/framework.md was evaluated as a motion reference. Its Swift/Objective-C libraries are not Android dependencies. Flutter's SpringSimulation and AnimatedScale implement the relevant interruptible motion.

## Build and validation

Pinned Flutter 3.38.5, Android SDK 36 (target 34), NDK 27.2.12479018, CMake 3.22.1, Java 21. Run `bash scripts/build-home.sh` at the repo root. It builds Rust for ARM64 and x86-64, analyzes Dart, runs widget tests and produces a signed APK. `scripts/test-home-emulator.sh` exercises real Android catalog/search/launching, drawer gestures, native-view startup and signed-release cold startup in an Android 14 emulator. PixelCopy captures include the actual native/Flutter composite.

Physical Xiaomi/HyperOS validation, 120 Hz frame measurements and battery impact remain unverified. No 120 fps or stability guarantee is made. The Android launcher role is changed only after the user selects it in Android's chooser. Uninstall uses Android's confirmation UI. Wallpaper import uses ACTION_OPEN_DOCUMENT, a 16 MiB input bound and downsampled decoding.

## Third-party notice

LiquidGlass Android — Copyright (c) 2025–2026 pandadog, MIT. Full license: `platform/flutter-home/android/liquidglass/LICENSE`. Upstream: https://github.com/QWEA0/Liquid-Glass-Android
