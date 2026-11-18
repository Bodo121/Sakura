#!/system/bin/sh
[ "$APATCH" = true ] || abort "Install using APatch Manager."
[ "$ARCH" = arm64 ] || abort "SakuraOS requires ARM64."
[ "$API" -ge 34 ] || abort "Android 14 (API 34) or newer required."
[ -f "$MODPATH/bin/sakurad" ] && [ -f "$MODPATH/bin/sakura" ] || abort "Compiled binaries missing."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
ui_print "SakuraOS native development services installed. Reboot to start."
ui_print "SELinux policy and boot images are unchanged."
ui_print "APKs run unprivileged; daemon access is root CLI only in this preview."
