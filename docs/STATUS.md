# Honest implementation status

The master specification is the roadmap. This checkout does **not** complete every requested feature. Build success is separate from installation and physical-device testing.

| Area | Implemented | Remaining |
|---|---|---|
| Runtime | lifecycle, cancellation flag, monotonic startup timer, structured JSON logs, atomic settings | bounded background executor, lifecycle-to-Android callbacks, recovery policy |
| IPC | typed bounded JSON frames, versioning, request IDs, timeouts, UDS, same-UID peer authentication | event subscriptions, automatic recovery/backoff, discovery registry |
| Service library | start/stop/restart, dependencies/cycle checks, child cleanup, three starts per minute, state | integration with daemon registry, dependency shutdown order, event-based child exit notification |
| Hardware | genuine proc/sys reads, memory, CPUs, frequencies, ZRAM, battery, charging, thermal, storage, network, kernel | utilization interval calculations, wider device-specific adapters; blocked data remains unavailable |
| UI | shared native Slint cards, scrolling, search, text inputs, buttons, theme toggle, density-aware NativeActivity | grids, icons, dialogs, spring motion, charts, full component suite, frame instrumentation |
| Settings | device/hardware dashboard, search, saved light/dark preference, host display information | organized categories, working system controls, detailed network adapters |
| Home 0.1.2 | Kotlin Activity, actual installed apps, Rust JNI search with fallback, favorite grid/pages/dock, long-press menus, photo/gradient wallpapers, swipe navigation, manual HOME role, icon/grid/label customization | folders, external widgets, drag layout editing, work profiles, icon packs, device performance validation; see HOME.md |
| Files | app-private browsing, metadata, new/mkdir/copy/move/rename/delete, sort, search, bounded text preview, document import/export | SAF tree browsing, image previews, ZIP inspection, recursive operations, context menus and operation dialogs |
| Core Manager | hardware snapshot, explicit APatch-approved fixed-command diagnostics request | rich metrics graphs, live service management, root provider abstraction wiring |
| Patch app | document import, trust-key file, verify/stage/apply/rollback owned-file generations | detailed diff/progress, key management UI, service activation orchestration |
| Terminal | real PTY shell as app UID, bounded output, native text input, CLI alias, themes | VT escape processing, multiple sessions, history UI, selection/copy/paste, font-size controls |
| Patch engine | Ed25519, SHA-256, semantic base versions, bounded copy/insert deltas, immutable staging, atomic state, rollback | key rotation, anti-rollback secure counters, nested files, power-loss fault injection, OS-level activation |
| APatch | ARM64 native binaries, private socket, module-local late start, logs, clean uninstall | physical taoyao installation and SELinux verification |
| SakuraPrivilege | portable capability and decision types; explicit diagnostics host action | complete provider/audit/approval service; no second root framework |
| SakuraBridge | documented research boundaries | implementation intentionally deferred |

Do not describe this as a finished operating system, complete launcher/file manager, zero-bug release, or measured 120 Hz framework. No real taoyao validation has occurred in this workspace. Android emulator tests are not claimed.
