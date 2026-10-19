#!/system/bin/sh
# mp_guard.sh — 投屏授权自动放行 + 投屏宝/哪吒汽车保活钉权（设备端守护）
# 以 shell(uid 2000) 身份运行，脱离 USB 后仍存活，直到重启。
# 部署:  adb push mp_guard.sh /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/mp_guard.sh
#        adb shell "nohup setsid /system/bin/sh /data/local/tmp/mp_guard.sh >/data/local/tmp/mp_guard.log 2>&1 &"
# 停止:  adb shell "pkill -f mp_guard.sh; cmd appops set com.sven.mycar PROJECT_MEDIA allow"

PKG=com.sven.mycar
NEZHA=com.hezhong.nezha
SELF=cn.luohui.mpguard
TMP=/data/local/tmp/mp_guard_win.xml
# 确认按钮文案候选（中英文都覆盖，ColorOS 语言切换后仍有效）
BTN='立即开始|开始投射|开始投屏|开始录制|投射|允许|确定|同意|Start now|Start casting|Start recording|Allow|OK'
# 需要钉住的授权项
OPS='PROJECT_MEDIA SYSTEM_ALERT_WINDOW RECORD_AUDIO FINE_LOCATION COARSE_LOCATION BACKGROUND_START_ACTIVITY RUN_ANY_IN_BACKGROUND START_FOREGROUND'
# 需要钉住的运行时权限
PERMS='android.permission.RECORD_AUDIO android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.READ_PHONE_STATE'
# 哪吒汽车：蓝牙钥匙依赖 BLE 扫描/连接；后台存活依赖 appops + 待机桶
NEZHA_OPS='RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK'
SELF_OPS='RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK SCHEDULE_EXACT_ALARM'
NEZHA_PERMS='android.permission.BLUETOOTH_SCAN android.permission.BLUETOOTH_CONNECT android.permission.BLUETOOTH_ADVERTISE android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.ACCESS_BACKGROUND_LOCATION android.permission.READ_PHONE_STATE android.permission.POST_NOTIFICATIONS'

TICK=0
PIN_EVERY=15          # 每 15 轮（约 15s）钉一次权限，避免频繁写 appops.xml
PIN_NEZHA_EVERY=60    # 哪吒相关权限/待机桶约每分钟复核一次即可

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" 2>/dev/null; }

pin_all() {
  for o in $OPS;  do cmd appops set "$PKG" "$o" allow 2>/dev/null; done
  for p in $PERMS; do pm grant "$PKG" "$p" 2>/dev/null; done
}

pin_keepalive() {
  for o in $NEZHA_OPS; do cmd appops set "$NEZHA" "$o" allow 2>/dev/null; done
  for o in $SELF_OPS; do cmd appops set "$SELF" "$o" allow 2>/dev/null; done
  for p in $NEZHA_PERMS; do pm grant "$NEZHA" "$p" 2>/dev/null; done
  for p in "$PKG" "$NEZHA" "$SELF"; do
    dumpsys deviceidle whitelist 2>/dev/null | grep -q "user,$p," \
      || dumpsys deviceidle whitelist "+$p" >/dev/null 2>&1
    [ "$(am get-standby-bucket "$p" 2>/dev/null | tr -d '\r')" = "5" ] \
      || am set-standby-bucket "$p" 5 >/dev/null 2>&1
  done
}

wake_neta() {
  pidof "$NEZHA" >/dev/null 2>&1 && return 0
  # 进程被清掉时补一发显式心跳；守护 App 的 bindService 会先把进程拉起
  am broadcast -a com.ingeek.vck.alive.service.ACTION_SDK_HEARTBEAT \
    -n "$NEZHA/com.ingeek.vck.alive.receiver.BootBroadcastReceiver" \
    --include-stopped-packages >/dev/null 2>&1
  log "哪吒进程不在，已发送心跳唤醒广播"
}

# 从 uiautomator dump 里找按钮中心坐标并点击；$1=要求 clickable
tap_by_text() {
  CLIK="$1"
  if [ "$CLIK" = "1" ]; then
    HIT=$(grep -oE "text=\"[^\"]*($BTN)[^\"]*\"[^>]*clickable=\"true\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$TMP" | head -1)
  else
    HIT=$(grep -oE "text=\"[^\"]*($BTN)[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$TMP" | head -1)
  fi
  [ -z "$HIT" ] && return 1
  # 注意：不能用 tr -dc '0-9,'，它会把 "[x1,y1][x2,y2]" 中间的 ][ 分隔符吃掉导致数字粘连
  QUAD=$(echo "$HIT" | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
  [ -z "$QUAD" ] && return 1
  set -- $QUAD
  X=$(( ($1 + $3) / 2 ))
  Y=$(( ($2 + $4) / 2 ))
  log "点击按钮 ($X,$Y) 来自 bounds [$1,$2][$3,$4]"
  input tap "$X" "$Y" 2>/dev/null
  return 0
}

dismiss() {
  # 第 1 档：直接回车，命中默认按钮，最快且无需 dump
  input keyevent 66 2>/dev/null
  sleep 0.35
  dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus' | grep -qiE 'projection|投射|MediaProjection' || return 0

  # 第 2 档：确认键 DPAD_CENTER
  input keyevent 23 2>/dev/null
  sleep 0.35
  dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus' | grep -qiE 'projection' || return 0

  # 第 3 档：读无障碍节点树，按文案定位坐标
  uiautomator dump /dev/tty >"$TMP" 2>/dev/null
  [ -s "$TMP" ] || uiautomator dump /data/local/tmp/mp_guard_win.xml >/dev/null 2>&1
  [ -s "$TMP" ] || return 1
  tap_by_text 1 && return 0
  tap_by_text 0 && return 0
  return 1
}

log "mp_guard 启动 pid=$$"
pin_all
pin_keepalive
wake_neta
log "首轮钉权完成：投屏宝 appops + 哪吒 BLE 权限 + Doze 白名单/待机桶"
while :; do
  F=$(dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp')

  # 命中投屏授权框：Activity 名含 projection，或 ColorOS 自定义的投射弹窗
  case "$F" in
    *[Pp]rojection*)
      log "检测到投屏授权框"
      i=1
      while [ $i -le 6 ]; do
        dismiss && { log "已自动放行 (第 $i 次尝试)"; break; }
        sleep 0.3
        i=$((i + 1))
      done
      [ $i -gt 6 ] && log "警告：6 次尝试后仍未放行，请检查按钮文案白名单"
      ;;
  esac

  TICK=$((TICK + 1))
  if [ $((TICK % PIN_EVERY)) -eq 0 ]; then
    # 自愈：任何一次"拒绝"都会把 PROJECT_MEDIA 打成 ignore，这里钉回来
    M=$(cmd appops get "$PKG" PROJECT_MEDIA 2>/dev/null | tr -d '\r')
    case "$M" in
      *allow*) : ;;
      *) log "PROJECT_MEDIA 漂移 -> $M，重置为 allow"; pin_all ;;
    esac
  fi
  if [ $((TICK % PIN_NEZHA_EVERY)) -eq 0 ]; then
    pin_keepalive
    wake_neta
  fi

  sleep 1
done
