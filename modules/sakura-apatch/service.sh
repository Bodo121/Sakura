#!/system/bin/sh
MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] && exit 0
umask 077
STATE=/data/adb/sakura
[ ! -L "$STATE" ] || exit 1
mkdir -p "$STATE"
chmod 0700 "$STATE"
# One launch only; no watchdog or unbounded restart loop. Daemon lock prevents duplicates.
[ ! -f "$STATE/daemon.log" ] || mv -f "$STATE/daemon.log" "$STATE/daemon.log.previous"
"$MODDIR/bin/sakurad" --state-dir "$STATE" >>"$STATE/daemon.log" 2>&1 &
