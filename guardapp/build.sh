#!/usr/bin/env bash
# build.sh — 不用 Gradle，手工用 aapt2 + javac + d8 + zipalign + apksigner 出 APK
# 本地 Windows 与 GitHub Actions (Linux) 共用；SDK 路径可用 ANDROID_SDK_ROOT/ANDROID_HOME 覆盖
set -euo pipefail
export MSYS_NO_PATHCONV=1

APP="$(cd "$(dirname "$0")" && pwd)"
cd "$APP"

SDK=""
for p in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
         "C:/Users/Administrator/AppData/Local/Android/Sdk" \
         "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
  if [ -n "$p" ] && [ -d "$p/build-tools" ]; then SDK="$p"; break; fi
done
[ -n "$SDK" ] || { echo "未找到 Android SDK，请设置 ANDROID_SDK_ROOT" >&2; exit 1; }

if [ -n "${ANDROID_BUILD_TOOLS:-}" ]; then
  BT="$ANDROID_BUILD_TOOLS"
elif [ -d "$SDK/build-tools/33.0.1" ]; then
  BT="$SDK/build-tools/33.0.1"
else
  BT="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
fi

if [ -n "${ANDROID_JAR:-}" ]; then
  AJ="$ANDROID_JAR"
elif [ -f "$SDK/platforms/android-33/android.jar" ]; then
  AJ="$SDK/platforms/android-33/android.jar"
else
  AJ="$(find "$SDK/platforms" -mindepth 2 -maxdepth 2 -name android.jar | sort -V | tail -1)"
fi
[ -f "$AJ" ] || { echo "未找到 android.jar: $SDK/platforms" >&2; exit 1; }

EXE=""
APKSIGNER="$BT/apksigner"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; APKSIGNER="$BT/apksigner.bat" ;;
esac

echo "SDK: $SDK"
echo "build-tools: $BT"
echo "android.jar: $AJ"

rm -rf build && mkdir -p build/obj build/dex build/gen

echo "== 1) aapt2 compile"
"$BT/aapt2$EXE" compile --dir res -o build/res.zip

echo "== 2) aapt2 link (+ R.java)"
"$BT/aapt2$EXE" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$AJ" \
  --java build/gen \
  --auto-add-overlay \
  build/res.zip

echo "== 3) javac"
javac -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$AJ" -d build/obj \
  $(find src build/gen -name "*.java")
echo "   classes: $(find build/obj -name '*.class' | wc -l)"

echo "== 4) d8 -> dex"
jar cf build/classes.jar -C build/obj .
# build-tools 33 的 d8.bat 依赖已被 JDK 17 移除的 java.ext.dirs，直接调 d8.jar
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$AJ" --output build/dex build/classes.jar
ls -la build/dex

echo "== 5) 把 classes.dex 塞进 apk"
cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)

echo "== 6) zipalign"
"$BT/zipalign$EXE" -f 4 build/unsigned.apk build/aligned.apk

echo "== 7) 签名"
KS="${MPGUARD_KS:-mpguard.ks}"
KS_PASS="${MPGUARD_KS_PASS:-mpguard123}"
KEY_PASS="${MPGUARD_KEY_PASS:-$KS_PASS}"
KEY_ALIAS="${MPGUARD_KEY_ALIAS:-mpguard}"
if [ ! -f "$KS" ]; then
  if [ "${CI:-}" = "true" ]; then
    echo "CI 缺少签名 keystore: $KS（请在仓库 Secrets 配置 MPGUARD_KS_BASE64）" >&2
    exit 1
  fi
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=MpGuard, OU=local, O=local, L=local, S=local, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" --out mp-guard.apk build/aligned.apk

echo "== 完成 =="
ls -la mp-guard.apk
"$APKSIGNER" verify --print-certs mp-guard.apk 2>&1 | head -4
