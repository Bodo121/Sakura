# Required on-device checklist — not yet performed

Record phone model/codename, Android build, kernel, APatch version, SELinux state, installation time and artifact SHA-256. Keep screenshots/logs and precise pass/fail results.

- Verify module installer rejects non-APatch/non-ARM64/API<34 inputs in a test harness.
- Confirm `getenforce` remains Enforcing before/after installation and reboot.
- Reboot once; verify daemon status, one instance, private 0700/0600 state/socket, no SELinux denial loop, no early-boot delay.
- Deny root access to another app and attempt daemon connection: expect rejection, never shared socket permissions.
- Install all six APKs, launch, rotate, background/resume, lock/unlock and recreate the surface. Verify no native crash with `adb logcat -b crash`.
- Settings: real display dimensions/density/refresh rate, theme persistence, unavailable hardware handling.
- Home: app search, launching all six packages, manual launcher chooser, original launcher retained.
- Files: import/cancel/export, duplicate destination, copy/rename/move, empty-directory deletion, text preview, traversal rejection, oversized input, permission denial. Rich image/ZIP previews are not implemented.
- Core: deny/approve explicit APatch diagnostics; ensure denial does not break read-only UI. Verify no automatic root request on launch.
- Patch: valid/invalid key/signature/hash, wrong base, delta bounds, interrupted staging, rollback, reboot between fsync/activation phases. No system partition writes.
- Terminal: PTY creation, shell echo, Unicode, CLI version/hardware, shell termination when UI closes, no automatic root shell.
- Measure cold/warm startup (multiple samples), PSS/RSS, IPC percentiles, idle CPU over at least 15 minutes, battery impact in comparable controlled sessions.
- Capture Android frame timing with Perfetto/FrameTimeline on the actual display mode. Report p50/p95/p99 and dropped frames; 120 Hz has an 8.33 ms budget, not a guaranteed outcome. Do not infer battery efficiency or frame timing from host tests.
- Disable/uninstall module; check process and state cleanup. Uninstall apps; verify stock launcher and device boot normally.
