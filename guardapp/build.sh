#!/usr/bin/env bash
# build.sh — 不用 Gradle，手工用 aapt2 + javac + d8 + zipalign + apksigner 出 APK
set -euo pipefail
export MSYS_NO_PATHCONV=1

SDK="C:/Users/Administrator/AppData/Local/Android/Sdk"
AJ="$SDK/platforms/android-33/android.jar"
BT="$SDK/build-tools/33.0.1"
APP="$(cd "$(dirname "$0")" && pwd)"
cd "$APP"
rm -rf build && mkdir -p build/obj build/dex build/gen

echo "== 1) aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "== 2) aapt2 link (+ R.java)"
"$BT/aapt2.exe" link -o build/base.apk \
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
"$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk

echo "== 7) 签名"
if [ ! -f mpguard.ks ]; then
  keytool -genkeypair -keystore mpguard.ks -storepass mpguard123 -keypass mpguard123 \
    -alias mpguard -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=MpGuard, OU=local, O=local, L=local, S=local, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks mpguard.ks --ks-pass pass:mpguard123 \
  --key-pass pass:mpguard123 --out mp-guard.apk build/aligned.apk

echo "== 完成 =="
ls -la mp-guard.apk
"$BT/apksigner.bat" verify --print-certs mp-guard.apk 2>&1 | head -4
