#!/system/bin/sh
MODDIR=${0%/*}
CLASSPATH="$MODDIR/smsrelay.jar" /system/bin/app_process /system/bin dev.smsrelay.Main ctl /data/adb/smsrelay stop >/dev/null 2>&1
# 卸载保留私有配置、队列和日志，由机主按需清理。
