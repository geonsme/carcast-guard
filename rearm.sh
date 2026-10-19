#!/usr/bin/env bash
# rearm.sh — 手机重启后重新拉起投屏授权守护（一条命令）
# 用法: 手机插上电脑并允许 USB 调试后执行  bash rearm.sh
export MSYS_NO_PATHCONV=1
SVEN="com.sven.mycar/com.sven.mycar.phone.control.MyCarControlService"
MINE="cn.luohui.mpguard/cn.luohui.mpguard.GuardService"

adb wait-for-device
echo "设备: $(adb get-serialno)"

# 1) 写回无障碍启用列表（settings put 不受 ColorOS 授权开关限制）
CUR=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
NEW="$SVEN:$MINE"
echo "重启前列表: ${CUR:-(空)}"
adb shell settings put secure enabled_accessibility_services "$NEW"
adb shell settings put secure accessibility_enabled 1
echo "已写回: $(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"

# 2) 兜底把投屏相关 appops 再钉一次（正常情况下它们本来就在）
P=com.sven.mycar
for op in PROJECT_MEDIA SYSTEM_ALERT_WINDOW; do
  adb shell "cmd appops set $P $op allow" >/dev/null 2>&1
done
echo "appops: $(adb shell cmd appops get $P PROJECT_MEDIA | tr -d '\r')"

sleep 3
echo "=== 绑定结果 ==="
adb shell "dumpsys accessibility" 2>&1 | tr -d '\r' | grep -E "Bound services" | head -2 | cut -c1-200

# 3) 若授权通道已开，顺手把自愈权限装上（可选，失败不影响上面）
adb shell pm grant cn.luohui.mpguard android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 \
  && echo "WRITE_SECURE_SETTINGS 已授予 -> 以后开机可完全自愈，无需再跑本脚本" \
  || echo "提示: 本次未能授予 WRITE_SECURE_SETTINGS（ColorOS 授权开关未开），下次重启仍需跑一次本脚本"
