#!/usr/bin/env bash
# rearm.sh — 重启/掉线后一条命令恢复：无障碍自愈、投屏宝与哪吒汽车保活
# 用法: 手机插上电脑并允许 USB 调试后执行  bash rearm.sh
set -u
export MSYS_NO_PATHCONV=1

ADB=${ADB:-}
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB=adb
  else
    for c in "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
             "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" \
             "$HOME/Android/Sdk/platform-tools/adb"; do
      [ -x "$c" ] && ADB="$c" && break
    done
  fi
fi
[ -n "$ADB" ] || { echo "未找到 adb，请用 ADB=/path/to/adb bash rearm.sh 指定"; exit 1; }

SVEN_PKG=com.sven.mycar
NEZHA=com.hezhong.nezha
SELF_PKG=cn.luohui.mpguard
SVEN="$SVEN_PKG/$SVEN_PKG.phone.control.MyCarControlService"
MINE="$SELF_PKG/cn.luohui.mpguard.GuardService"

A() { "$ADB" "$@"; }

A wait-for-device
echo "设备: $(A get-serialno | tr -d '\r')"

echo "== 1) 写回无障碍启用列表（保留已有条目）=="
CUR=$(A shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
[ "$CUR" = "null" ] && CUR=""
NEW="$CUR"
case "$NEW" in *com.sven.mycar*) ;; *) NEW="${NEW:+$NEW:}$SVEN" ;; esac
case "$NEW" in *cn.luohui.mpguard*) ;; *) NEW="${NEW:+$NEW:}$MINE" ;; esac
echo "原列表: ${CUR:-(空)}"
if [ "$NEW" != "$CUR" ]; then
  A shell settings put secure enabled_accessibility_services "$NEW"
  echo "已补写: $NEW"
else
  echo "已包含两条服务，无需补写"
fi
A shell settings put secure accessibility_enabled 1
echo "accessibility_enabled=$(A shell settings get secure accessibility_enabled | tr -d '\r')"

echo "== 2) 钉住投屏宝授权 =="
for op in PROJECT_MEDIA SYSTEM_ALERT_WINDOW RECORD_AUDIO FINE_LOCATION COARSE_LOCATION \
          BACKGROUND_START_ACTIVITY RUN_ANY_IN_BACKGROUND START_FOREGROUND; do
  A shell "cmd appops set $SVEN_PKG $op allow" >/dev/null 2>&1
done
for p in RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_PHONE_STATE; do
  A shell "pm grant $SVEN_PKG android.permission.$p" >/dev/null 2>&1
done
echo "PROJECT_MEDIA: $(A shell cmd appops get $SVEN_PKG PROJECT_MEDIA | tr -d '\r' | tail -1)"

echo "== 3) 钉住哪吒汽车蓝牙钥匙与后台运行权限 =="
for op in RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do
  A shell "cmd appops set $NEZHA $op allow" >/dev/null 2>&1
done
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE \
         ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION \
         READ_PHONE_STATE POST_NOTIFICATIONS; do
  A shell "pm grant $NEZHA android.permission.$p" >/dev/null 2>&1
done
A shell pm grant "$SELF_PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 \
  && echo "WRITE_SECURE_SETTINGS 已授予（开机可完全自愈）" \
  || echo "提示: WRITE_SECURE_SETTINGS 未授予，自愈需要打开 App 或重跑本脚本"
for op in RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do
  A shell "cmd appops set $SELF_PKG $op allow" >/dev/null 2>&1
done
A shell "cmd appops set $SELF_PKG SCHEDULE_EXACT_ALARM allow" >/dev/null 2>&1 \
  && echo "SCHEDULE_EXACT_ALARM 已授予（10 分钟精确兜底）" \
  || echo "提示: 精确闹钟未授予，将退化为非精确兜底"
A shell "cmd appops set $SELF_PKG REQUEST_INSTALL_PACKAGES allow" >/dev/null 2>&1 \
  && echo "REQUEST_INSTALL_PACKAGES 已授予（App 内更新可静默进入安装器）" \
  || echo "提示: 安装未知应用未预授权，首次内部更新需在系统弹窗中允许"

echo "== 4) Doze 白名单 / 待机桶 =="
for p in "$SVEN_PKG" "$NEZHA" "$SELF_PKG"; do
  if ! A shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "user,$p,"; then
    A shell "dumpsys deviceidle whitelist +$p" >/dev/null 2>&1
  fi
  B=$(A shell "am get-standby-bucket $p" 2>/dev/null | tr -d '\r')
  [ "$B" = "5" ] || A shell "am set-standby-bucket $p 5" >/dev/null 2>&1
  W=$(A shell dumpsys deviceidle whitelist 2>/dev/null | grep -c "user,$p," | tr -d '\r')
  B=$(A shell "am get-standby-bucket $p" 2>/dev/null | tr -d '\r')
  echo "  $p: whitelist=$W bucket=$B"
done

echo "== 5) 打开守护 App 触发自愈与哪吒绑定 =="
A shell am start -n "$SELF_PKG/.StatusActivity" >/dev/null 2>&1
sleep 3

echo "== 6) 结果 =="
A shell dumpsys accessibility 2>/dev/null | tr -d '\r' | grep -A2 "Bound services" | head -3 | cut -c1-220
NID=$(A shell pidof "$NEZHA" 2>/dev/null | tr -d '\r')
if [ -n "$NID" ]; then
  echo "哪吒进程: $NID"
else
  echo "哪吒进程: 未启动（请打开一次哪吒 App，或确认它已安装）"
fi
MQTT=$(A shell dumpsys activity services "$NEZHA" 2>/dev/null \
  | awk '/DMqttService c:/{f=1} f&&/recentCallingPackage=/{sub(/.*recentCallingPackage=/,""); gsub(/^[ \t]+/,""); print; exit}' \
  | tr -d '\r')
echo "哪吒 MQTT 绑定来源: ${MQTT:-未找到（App 未安装或尚未绑定）}"
JOB=$(A shell "dumpsys jobscheduler | grep -c 'JOB #.*RearmJobService'" 2>/dev/null | tr -d '\r')
echo "自愈定时任务: ${JOB:-0} 条"
echo
echo "提示: 首次写回无障碍时 ColorOS 可能弹「保持开启」，请选择保持开启。"
