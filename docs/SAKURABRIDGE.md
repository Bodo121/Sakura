# SakuraBridge research boundary

SakuraBridge is future Android compatibility, separate from all native apps. No implementation is claimed in preview 0.1.0.

| Component | Compatibility obligation | Primary reference |
|---|---|---|
| ART | DEX loading, AOT/JIT, runtime JNI, class libraries and matching platform API behavior | https://source.android.com/docs/core/runtime |
| Bionic | Android libc/linker and native ABI; Android native libraries cannot generally be loaded into a glibc process | https://android.googlesource.com/platform/bionic/ |
| Binder | kernel IPC plus servicemanager, stable AIDL/version expectations, identity/security policy | https://source.android.com/docs/core/architecture/aidl |
| Framework | application/activity/window/content/provider/resource/notification services | https://source.android.com/docs/core/architecture |
| Packages | APK signatures, UIDs, permissions, intents, install/update rules and resource splits | https://source.android.com/docs/security/features/apksigning |
| Lifecycle | activities/services/broadcast receivers, process death, saved state, background limits | https://developer.android.com/guide/components/activities/activity-lifecycle |
| Graphics | SurfaceFlinger/BufferQueue, gralloc, EGL/Vulkan and sync fences, input and display lifecycle | https://source.android.com/docs/core/graphics |
| Permissions | per-app identity, storage isolation, SELinux domains, runtime permission grants | https://source.android.com/docs/security/features/permissions |

A practical first experiment would isolate a matching AOSP userspace and integrate its display/input output, rather than reimplement all Android APIs. Such a subsystem still contains a large Android environment and needs kernel support; it does not turn the native Sakura framework into Android. Running arbitrary APKs is not guaranteed.

Google Play Services is proprietary, separately licensed, and many apps depend on its APIs, Play Integrity, DRM, push, billing and hardware attestation. A compatibility container cannot promise licensing availability, certified integrity results or banking-app compatibility. Do not spoof or fabricate these results. Evaluate an AOSP-only compatibility target first.
