#!/system/bin/sh
[ "$API" -ge 25 ] || abort "需要 Android 7.1 / API 25 以上"
umask 077
HOME_DIR=/data/adb/smsrelay
mkdir -p "$HOME_DIR/logs" "$HOME_DIR/run"
chmod 700 "$HOME_DIR" "$HOME_DIR/logs" "$HOME_DIR/run"
if [ ! -f "$HOME_DIR/config.json" ]; then
  cp "$MODPATH/config/config.json" "$HOME_DIR/config.json"
  chmod 600 "$HOME_DIR/config.json"
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
for f in service.sh action.sh uninstall.sh smsrelay webctl; do set_perm "$MODPATH/$f" 0 0 0755; done
ui_print "smsrelay 已安装；配置和队列升级保留。首次配置默认关闭。"
ui_print "仅 K40 HyperOS Android 13 真机验证；其他系统以兼容记录为准。"
ui_print "配置：/data/adb/smsrelay/config.json"
