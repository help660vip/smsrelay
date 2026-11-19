#!/system/bin/sh
MODDIR=${0%/*}
umask 077
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then exit 0; fi
mkdir -p /data/adb/smsrelay/run
chmod 700 /data/adb/smsrelay /data/adb/smsrelay/run
# 短信数据库位于用户的加密数据区。开机完成与首次解锁是两个状态。
user_unlocked() {
  case "$(am get-started-user-state 0 2>/dev/null)" in
    RUNNING_UNLOCKED) return 0 ;;
    RUNNING_*|BOOTING|STOPPING|SHUTDOWN) return 1 ;;
  esac
  case "$(getprop sys.user.0.ce_available)" in
    1|true) return 0 ;;
    0|false) return 1 ;;
  esac
  # 旧系统可能没有上述查询，继续原有启动流程。
  [ "$(getprop ro.build.version.sdk)" -lt 29 ]
}

state_file=/data/adb/smsrelay/run/last-error.txt
n=0
while [ "$(getprop sys.boot_completed)" != 1 ] || ! user_unlocked; do
  if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then exit 0; fi
  if [ "$n" -eq 0 ]; then
    printf '%s\n' 'startup_waiting_for_unlock' > "$state_file"
  fi
  if [ "$n" -ge 90 ]; then
    printf '%s\n' 'startup_wait_timeout; unlock then use start' > "$state_file"
    exit 0
  fi
  sleep 2
  n=$((n+1))
done
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then exit 0; fi
rm -f "$state_file"
export CLASSPATH="$MODDIR/smsrelay.jar"
nohup /system/bin/app_process /system/bin --nice-name=smsrelay-supervise dev.smsrelay.Main supervise /data/adb/smsrelay >/dev/null 2>&1 </dev/null &
