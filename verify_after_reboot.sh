#!/usr/bin/env bash
# verify_after_reboot.sh — 重启后核对投屏宝与哪吒汽车的授权、保活与自愈状态
# 用法: 手机重启并重新连上 adb 后，在 Git Bash 里执行:  bash verify_after_reboot.sh
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
[ -n "$ADB" ] || { echo "未找到 adb，请用 ADB=/path/to/adb bash verify_after_reboot.sh 指定"; exit 1; }

S=com.sven.mycar
N=com.hezhong.nezha
G=cn.luohui.mpguard
pass=0; fail=0; warn=0

A() { "$ADB" "$@"; }
ck() {
  if [ "$2" = "$3" ]; then
    printf "  [OK]   %-34s %s\n" "$1" "$2"; pass=$((pass+1))
  else
    printf "  [%s] %-34s %s  (期望 %s)\n" "$4" "$1" "$2" "$3"
    [ "$4" = "FAIL" ] && fail=$((fail+1)) || warn=$((warn+1))
  fi
}
op_state() {
  A shell "cmd appops get $1 $2" 2>/dev/null | tr -d '\r' \
    | awk '
        $1=="Default" && $2=="mode:" { v=$3 }
        $1 ~ /:$/ { v=$2 }
        v!="" { sub(/;.*/,"",v); print v; exit }
      '
}
perm_state() {
  if A shell "dumpsys package $1" 2>/dev/null | tr -d '\r' \
      | grep -q "android.permission.$2: granted=true"; then echo true; else echo false; fi
}
installed() {
  [ -n "$(A shell "pm path $1" 2>/dev/null | tr -d '\r')" ] && echo yes || echo no
}

A wait-for-device
echo "设备: $(A get-serialno | tr -d '\r')"

echo "=== 1. 投屏宝核心 appops（重启是否保住）==="
for op in PROJECT_MEDIA SYSTEM_ALERT_WINDOW RECORD_AUDIO FINE_LOCATION COARSE_LOCATION \
          BACKGROUND_START_ACTIVITY RUN_ANY_IN_BACKGROUND START_FOREGROUND; do
  ck "$op" "$(op_state "$S" "$op" | tr -d '\r')" "allow" "FAIL"
done

echo "=== 2. 投屏宝运行时权限 ==="
for perm in RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  ck "$perm" "$(perm_state "$S" "$perm")" "true" "FAIL"
done

echo "=== 3. 哪吒汽车：安装、蓝牙钥匙权限与后台运行 ==="
ck "哪吒已安装" "$(installed "$N")" "yes" "FAIL"
for perm in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE \
            ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  ck "$perm" "$(perm_state "$N" "$perm")" "true" "FAIL"
done
for op in RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do
  ck "$op" "$(op_state "$N" "$op")" "allow" "FAIL"
done

echo "=== 4. 无障碍服务（系统在 boot 完成后重新 bind）==="
EN=$(A shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
case "$EN" in
  *com.sven.mycar*cn.luohui.mpguard*|*cn.luohui.mpguard*com.sven.mycar*)
    ck "enabled_accessibility_services" "两条都在" "两条都在" "FAIL" ;;
  *) ck "enabled_accessibility_services" "不完整" "两条都在" "FAIL" ;;
esac
BOUND=$(A shell dumpsys accessibility 2>/dev/null | tr -d '\r' | grep -A2 "Bound services" \
  | grep -c "车机投屏宝\|车控守护")
ck "已绑定服务数" "$BOUND" "2" "WARN"
ACC=$(A shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r')
ck "accessibility_enabled" "$ACC" "1" "FAIL"

echo "=== 5. 保活条件：Doze 白名单与待机桶 ==="
for p in "$S" "$N" "$G"; do
  WL=$(A shell dumpsys deviceidle whitelist 2>/dev/null | grep -c "user,$p," | tr -d '\r')
  ck "whitelist $p" "$WL" "1" "WARN"
  BKT=$(A shell "am get-standby-bucket $p" 2>/dev/null | tr -d '\r')
  ck "bucket $p" "$BKT" "5" "WARN"
done

echo "=== 6. 守护 App 与哪吒保活链路 ==="
ck "WRITE_SECURE_SETTINGS" "$(perm_state "$G" WRITE_SECURE_SETTINGS)" "true" "FAIL"
ck "SCHEDULE_EXACT_ALARM" "$(op_state "$G" SCHEDULE_EXACT_ALARM | tr -d '\r')" "allow" "WARN"
ALARM=$(A shell "dumpsys alarm | grep -c 'action $G.REARM_ALARM'" 2>/dev/null | tr -d '\r')
ck "兜底闹钟在册" "$ALARM" "1" "WARN"
NALARM=$(A shell "dumpsys alarm | grep -c 'action com.ingeek.vck.alive.service.ACTION_SDK_HEARTBEAT.*ComponentInfo{$N/'" 2>/dev/null | tr -d '\r')
ck "哪吒直连心跳闹钟在册" "$NALARM" "1" "WARN"
JOB=$(A shell "dumpsys jobscheduler | grep -c 'JOB #.*RearmJobService'" 2>/dev/null | tr -d '\r')
ck "自愈定时任务在册" "$JOB" "1" "WARN"
NID=$(A shell pidof "$N" 2>/dev/null | tr -d '\r')
if [ -n "$NID" ]; then pass=$((pass+1)); printf "  [OK]   %-34s %s\n" "哪吒进程存活" "$NID"
else fail=$((fail+1)); printf "  [FAIL] %-34s %s  (期望 有 pid)\n" "哪吒进程存活" "未启动"; fi
MQTT=$(A shell dumpsys activity services "$N" 2>/dev/null | grep -c "DMqttService c:" | tr -d '\r')
ck "哪吒 MQTT 服务" "$MQTT" "1" "FAIL"
RECENT=$(A shell dumpsys activity services "$N" 2>/dev/null \
  | awk '/DMqttService c:/{f=1} f&&/recentCallingPackage=/{sub(/.*recentCallingPackage=/,""); gsub(/^[ \t]+/,""); print; exit}' \
  | tr -d '\r')
ck "MQTT 绑定来源" "${RECENT:-未知}" "$G" "WARN"

echo
echo "汇总: OK=$pass  FAIL=$fail  需留意=$warn"
if [ "$fail" -gt 0 ]; then
  echo "有 FAIL：先执行  bash rearm.sh  修复；仍不行则打开一次「车控守护」App。"
elif [ "$warn" -gt 0 ]; then
  echo "核心链路正常；WARN 多为首次启动尚未绑定或系统节奏差异，可稍后重跑本脚本。"
else
  echo "全部通过：投屏宝授权、哪吒蓝牙钥匙权限与双通道保活均在线。"
fi
