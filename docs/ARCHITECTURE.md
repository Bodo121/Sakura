# Architecture

Portable crates own runtime lifecycle, hardware snapshots, IPC, service supervision, signed update generations and capabilities. The SDK reexports these interfaces. The six app entry points select one shared Rust/Slint application model rather than duplicating functionality.

Android is a temporary host. Each APK contains a thin NativeActivity subclass, a Rust `cdylib`, and an unprivileged Rust CLI. Slint manages the Android surface/input lifecycle. Java is isolated in `platform/android-host`: document pickers, basic device APIs, explicit fixed diagnostics request, and launching known Sakura packages. Native app logic does not use ART APIs directly. The Home filter is an optional host convenience, not an OS architecture dependency.

The APatch module launches one root-owned `sakurad`. Its private directory and UDS only admit root peers. Requests are read-only except authenticated shutdown. No root file-browser, shell-exec IPC command, arbitrary path command, permissive SELinux mode or broad SELinux allow policy exists. Core Manager may explicitly invoke APatch's approval flow for a hardcoded CLI diagnostic operation. The UI otherwise reads unprivileged interfaces and reports unavailable values.

The service manager is a reusable library, currently separate from the minimal daemon. It supervises configured executable paths and validates dependencies. The host must call health checks in response to its event loop. No watchdog polling thread is created. It is not yet a complete init system.

The update store is data-only: immutable generations and an atomic state file. Activation intent is metadata (assets reload, service restart, process replacement, reboot required). Activation does not yet execute these intents. Never claim a service was hot-replaced just because its new file generation is active.

Standalone migration needs boot/init integration, a display compositor/input stack, device drivers/HAL replacements, secure application identities, package installation, persistent settings and a system lifecycle. Reusing a Linux-derived SakuraKernel does not make the userspace Android; the host adapter must be replaced. Proprietary Qualcomm HALs can still depend on Android Binder/Bionic services and require substantial device work. There is no promise that the stock kernel provides a complete standalone hardware interface.
