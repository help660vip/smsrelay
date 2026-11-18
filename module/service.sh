#!/system/bin/sh
MODDIR=${0%/*}
umask 077
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then exit 0; fi
mkdir -p /data/adb/smsrelay/run
chmod 700 /data/adb/smsrelay /data/adb/smsrelay/run
# Bounded boot readiness wait, never a resident watchdog.
n=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$n" -lt 90 ]; do
  sleep 2
  n=$((n+1))
done
export CLASSPATH="$MODDIR/smsrelay.jar"
nohup /system/bin/app_process /system/bin --nice-name=smsrelay-supervise dev.smsrelay.Main supervise /data/adb/smsrelay >/dev/null 2>&1 </dev/null &
