#!/system/bin/sh
MODDIR=${0%/*}
export CLASSPATH="$MODDIR/smsrelay.jar"
exec /system/bin/app_process /system/bin --nice-name=smsrelay-menu dev.smsrelay.Main menu /data/adb/smsrelay
