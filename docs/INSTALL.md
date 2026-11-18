# Installation and recovery

These are developer-test instructions. ARM64 compilation and APK signature checks do not prove compatibility with a particular APatch/SELinux build.

1. Install `SakuraOS-Developer-APatch-v0.1.0.zip` in **APatch Manager**. Android 14/API 34 or newer, ARM64 and APatch are checked at install time. Never flash this ZIP in recovery.
2. Reboot once to invoke its module-local `service.sh`. It does not change the boot image, kernel, launcher, Android framework, vendor partition or SELinux policy.
3. In an existing terminal, explicitly request APatch root authorization and run:

```sh
su
/data/adb/modules/sakuraos_dev/bin/sakura version
/data/adb/modules/sakuraos_dev/bin/sakura status
/data/adb/modules/sakuraos_dev/bin/sakura diagnostics
/data/adb/modules/sakuraos_dev/bin/sakura hardware
/data/adb/modules/sakuraos_dev/bin/sakura logs
```

If status fails, read `/data/adb/sakura/daemon.log` and inspect SELinux denials. Keep enforcing enabled. Do not apply permissive or broad allow rules. No repeated automatic restart is attempted.

4. Install all six APKs normally. They run as ordinary isolated Android application UIDs. The apps need no root for native UI, private files or terminal sessions.
5. In Core Manager, **Authorize diagnostics** requests APatch approval for one fixed read-only CLI command. After approval tap Refresh. Denial must not stop the app from working. Other Android apps cannot connect directly to the daemon's root-only 0600 socket under a 0700 directory.
6. Home can be opened independently. Android may offer it in its default Home-app selector. Select it only manually; the module never changes your launcher.

Files: choose **Import document**, choose a file in Android's picker, then Refresh. The file is copied into the app's private storage (16 MiB maximum). Select a file, enter `copy NAME`, `rename NAME`, `move NAME`, `delete-confirm`, `export`, `mkdir NAME` or `new NAME`, then **Run command**. A selected directory opens; deletion of a directory requires it to be empty. Export uses Android's create-document picker. App storage is separate for each app.

Patch: import a deliberately trusted raw 32-byte Ed25519 public key named `patch-public.key`, then the `.patch` document. Enter its filename and tap Verify & stage, then Apply staged. A trust key imported from an untrusted package is not trustworthy. Never import a private signing key into an app. Rollback selects the previous owned generation, when present. Active generations are data files; no Android or running executable modification occurs.

Terminal: type a command and Send. `sakura version` and `sakura hardware` use the packaged CLI. Root daemon status requires explicit approval through another terminal or Core Manager. The Sakura terminal does not automatically call `su`.

## Stop/remove

Run `/data/adb/modules/sakuraos_dev/bin/sakura service stop` after root authorization, or remove the module in APatch Manager and reboot. Uninstall attempts authenticated graceful shutdown and removes only `/data/adb/sakura`. Uninstall each APK normally to delete its own private data. Return Android's default Home selection to your usual launcher if you selected Sakura Home.

If the module misbehaves, disable `sakuraos_dev` in APatch Manager and reboot. It uses no blocking early-boot script and does not patch the boot image. With existing authorized ADB root access, creating `/data/adb/modules/sakuraos_dev/disable` disables it next boot. Do not change other modules. Follow APatch's official rescue procedure if APatch itself is inaccessible.
