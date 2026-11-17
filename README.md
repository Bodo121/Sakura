# SakuraOS Developer Preview 0.1.0

A real Rust workspace for an independent mobile userspace, currently hosted by Android 14. **This is an early implementation, not the complete operating system or a daily-driver release.** Sakura Home 0.1.2 now uses a Kotlin Android interface and Rust search; see [the launcher guide](docs/HOME.md). The other five app packages remain Slint-based prototypes. Android is a development host; the launcher requires no APatch module.

## Build

Install stable Rust, JDK 17+, Python 3, zip, Android SDK platform 34, build-tools 35.0.0 and NDK 27.2.12479018. Set `ANDROID_HOME`. Then:

```sh
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cargo build --locked -p sakura -p sakurad
python3 scripts/integration.py
bash scripts/build-arm64.sh
python3 scripts/package-module.py
bash scripts/build-apps.sh
```

Linux UI builds use Winit and dynamically loaded fontconfig. On Ubuntu install fontconfig, libxkbcommon, Wayland development libraries and a working display server. `cargo run -p sakura-settings` starts a desktop app. No display is needed for core tests. Both Linux x86-64 and aarch64 are intended; only actual build results establish validation.

## Install and remove

See [installation](docs/INSTALL.md), [feature status](docs/STATUS.md), [architecture](docs/ARCHITECTURE.md), [patch format](docs/PATCH.md), [SDK](docs/SDK.md), [security](docs/SECURITY.md), [device tests](docs/DEVICE_TESTS.md), and [Android compatibility research](docs/SAKURABRIDGE.md).

APatch packaging follows the official [APM guide](https://apatch.dev/apm-guide.html). Android rendering uses Slint's [NativeActivity backend](https://docs.slint.dev/latest/docs/rust/slint/android/). Slint was selected over direct wgpu to reuse its native widgets, text shaping, layout and Android lifecycle handling. Rendering on Android uses Slint's Android backend (including Skia); software rendering is available for portable development. A 120 Hz performance claim requires measurements on a real device.

## Deliverables

Build scripts generate `SakuraOS-Developer-APatch-v0.1.0.zip`, `SakuraOS-Apps-v0.1.0.zip`, and six signed APKs in `dist/apps`. Source packaging and SHA-256 checksums use `python3 scripts/package-source.py`. GitHub Actions builds all targets and uploads artifacts. A tag release requires explicit device validation evidence; no tag is automatically created.

Local development signing persists in `.local/sakura-debug.jks`. It is excluded from source archives and Git. For consistent CI signing, configure the protected repository secret `SAKURA_DEBUG_KEYSTORE_BASE64` with that development keystore (alias `sakura-debug`, password `android`). Without it, each clean CI run generates a new development identity: Android requires uninstall before installing an APK signed by a different key. Never use this development key for a production release. No signing key is committed, cached publicly, or included in artifacts.

SakuraOS-owned updates operate on private immutable file generations, not Android partitions. APK replacement, executing newly staged binaries, application restart orchestration and live executable-memory patching are not implemented.
