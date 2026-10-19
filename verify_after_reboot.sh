#!/usr/bin/env bash
# verify_after_reboot.sh — 重启后核对车机投屏宝的授权与守护状态
# 用法: 手机重启并重新连上 adb 后，在 Git Bash 里执行:  bash verify_after_reboot.sh
export MSYS_NO_PATHCONV=1
P=com.sven.mycar
pass=0; fail=0; warn=0
ck()  { if [ "$2" = "$3" ]; then printf "  [OK]   %-34s %s\n" "$1" "$2"; pass=$((pass+1));
      else printf "  [%s] %-34s %s  (期望 %s)\n" "$4" "$1" "$2" "$3"; [ "$4" = "FAIL" ] && fail=$((fail+1)) || warn=$((warn+1)); fi; }

echo "=== 1. 投屏核心 appops（重启是否保住）==="
for op in PROJECT_MEDIA SYSTEM_ALERT_WINDOW RECORD_AUDIO FINE_LOCATION COARSE_LOCATION BACKGROUND_START_ACTIVITY RUN_ANY_IN_BACKGROUND START_FOREGROUND; do
  v=$(adb shell "cmd appops get $P $op" 2>/dev/null | tr -d '\r' | awk '{print $2}')
  [ -z "$v" ] && v=$(adb shell "cmd appops get $P $op" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  ck "$op" "${v:-空}" "allow" "FAIL"
done

echo "=== 2. 运行时权限（存 packages.xml，应持久）==="
for perm in RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_PHONE_STATE CAMERA POST_NOTIFICATIONS; do
  v=$(adb shell "dumpsys package $P" 2>/dev/null | grep -oE "android.permission.$perm: granted=(true|false)" | grep -oE '(true|false)$')
  ck "$perm" "${v:-未找到}" "true" "FAIL"
done

echo "=== 3. 无障碍服务（系统在 boot 完成后重新 bind）==="
en=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
case "$en" in *"$P"*) ck "enabled_accessibility_services" "含 $P" "含 $P" OK ;;
                    *) ck "enabled_accessibility_services" "未包含" "含 $P" FAIL ;; esac
bound=$(adb shell "dumpsys accessibility" 2>/dev/null | grep -c "Bound services:.*$P\|ServiceInfo.*$P")
ck "服务已被 bind" "$bound" "1" WARN
acc_on=$(adb shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r')
ck "accessibility_enabled" "$acc_on" "1" FAIL

echo "=== 4. Doze 白名单与保活 ==="
wl=$(adb shell "dumpsys deviceidle whitelist" 2>/dev/null | grep -c "$P")
ck "deviceidle 白名单" "$wl" "1" WARN
bkt=$(adb shell am get-standby-bucket $P 2>/dev/null | tr -d '\r')
ck "standby-bucket" "$bkt" "5" WARN

echo "=== 5. shell 层进程（重启后必然为 0，这是设计如此）==="
g=$(adb shell "ps -A -o NAME 2>/dev/null | grep -c mp_guard" 2>/dev/null | tr -d '\r')
ck "mp_guard 守护进程" "${g:-0}" "0" WARN
h=$(adb shell "ps -A -o NAME 2>/dev/null | grep -c adbserver" 2>/dev/null | tr -d '\r')
ck "hh.jar(MyNewServer) 存活" "${h:-0}" "0" WARN

echo
echo "汇总: OK=$pass  FAIL=$fail  需留意=$warn"
echo "若第 1 节出现 FAIL 而第 2/3 节全 OK => appops 未持久化，需走『自建无障碍 App』或每次重启后重新 pin。"
echo "重新 pin 一条命令: adb shell \"cmd appops set $P PROJECT_MEDIA allow; cmd appops set $P SYSTEM_ALERT_WINDOW allow\""
