# SakuraOS GSI v0.2.0

**UNTESTED DEVELOPER BUILD — FIRST BOOT NOT VERIFIED**

An unsuccessful build report is not an installable operating system. If the artifact contains no `SakuraOS-GSI-v0.2.0-arm64.img.xz`, image generation failed or was blocked. Do not flash APK archives, this report, or a renamed stock image.

## Before manual installation

No automated flashing, bootloader unlocking, wiping, AVB changes or kernel modifications are performed by this project. A GSI installation can erase data and can fail to boot. Back up data, authentication keys and original firmware. Keep a working USB cable, host platform-tools and a verified recovery route.

The supplied `D:\XIAOMI\...\taoyao_eea_global_images_OS1.0.39.0.ULIEUXM_14.0` path is not accessible in the cloud workspace. Its contents, partition images, checksums and recovery scripts have **not** been inspected. Merely having that folder does not establish that recovery is ready.

On your host, inspect the actual firmware folder and verify it contains the official taoyao fastboot images and matching flash scripts. Preserve these unchanged. Do not use a script that locks the bootloader with a custom system installed. Do not mix another region/device's boot or vendor firmware.

Read the build report before using any generated image. All build-time checks must succeed; the report must say `image_generated: true`. Run `sha256sum -c SakuraOS-GSI-v0.2.0-SHA256SUMS.txt`, then `xz -t SakuraOS-GSI-v0.2.0-arm64.img.xz`. Decompress to a separate file and compare its byte length to the actual available logical system partition. Do not assume a device's advertised storage capacity is available for the system partition.

## Device information to collect without writing

While stock Android is running, collect `adb shell getprop`, `adb shell cat /proc/mounts`, and, through your existing authorized APatch shell if needed, `lpdump`. These establish system-as-root, Binder architecture, vendor VNDK level, dynamic partition layout and available super space. From bootloader/fastbootd, `fastboot getvar current-slot` and `fastboot getvar is-userspace` are read-only checks. Avoid sharing identifiers from a full getprop dump publicly.

The partition layout and AVB state have not been established here, so this guide deliberately supplies no speculative `flash system_a`, partition deletion, resize, wipe or vbmeta command. Once those inputs are inspected, choose the appropriate manual fastbootd or supported DSU route. Installing an app or APatch module does not install the GSI.

## Recovery

If a manually flashed image fails, return to the verified stock recovery/fastboot workflow for **taoyao** using the exact official firmware you backed up. Restore the partitions changed during your installation according to the inspected stock scripts; do not blindly relock. If data encryption prevents access, preserve backups and use the manufacturer's documented recovery procedure. This build has no verified device-specific recovery automation.

## Unverified behavior

First boot, data decryption, Goodix FOD, camera HAL, modem/IMS/VoLTE and emergency calls, speakers/microphones, Wi-Fi, Bluetooth, NFC, DRM, sensors, charging, thermal behavior, 120 Hz, suspend and battery consumption are unverified. No boot or daily-driver claim is made.

Stock SystemUI, PermissionController, telephony, providers and fallback applications remain. See the per-app report for delegated functionality and unfinished features; a package appearing in the APK list does not certify that it replaces every stock contract.
