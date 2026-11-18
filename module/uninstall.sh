#!/system/bin/sh
MODDIR=${0%/*}
CLASSPATH="$MODDIR/smsrelay.jar" /system/bin/app_process /system/bin dev.smsrelay.Main ctl /data/adb/smsrelay stop >/dev/null 2>&1
# Keep private configuration, queue and logs. Delete only explicitly by the owner.
