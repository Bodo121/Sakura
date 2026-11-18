#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/sakura
[ ! -L "$STATE" ] || exit 1
# Authenticated graceful shutdown; never kill a PID read from an untrusted file.
"$MODDIR/bin/sakura" service stop >/dev/null 2>&1
# Fixed Sakura-owned state only. No external storage or other modules.
rm -rf /data/adb/sakura
