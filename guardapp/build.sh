#!/usr/bin/env bash
# build.sh — 不用 Gradle，手工用 aapt2 + javac + d8 + zipalign + apksigner 出 APK
set -euo pipefail
export MSYS_NO_PATHCONV=1

AJ="C:/temp/apk/sdk/full/platforms/android-34/android.jar"
BT="/c/temp/apk/sdk/bt/android-14"
APP="/c/Users/Administrator/Documents/Qoder/2026-10-08/d7343905/carcast/guardapp"
cd "$APP"
rm -rf build && mkdir -p build/obj build/dex

echo "== 1) aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "== 2) aapt2 link"
"$BT/aapt2.exe" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$AJ" \
  --auto-add-overlay \
  build/res.zip

echo "== 3) javac"
javac -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$AJ" -d build/obj \
  $(find src -name "*.java")
echo "   classes: $(find build/obj -name '*.class' | wc -l)"

echo "== 4) d8 -> dex"
jar cf build/classes.jar -C build/obj .
"$BT/d8.bat" --release --lib "$AJ" --output build/dex build/classes.jar
ls -la build/dex

echo "== 5) 把 classes.dex 塞进 apk"
cp build/base.apk build/unsigned.apk
cd build/dex && jar uf ../unsigned.apk classes.dex && cd ..

echo "== 6) zipalign"
"$BT/zipalign.exe" -f 4 unsigned.apk aligned.apk

echo "== 7) 签名"
if [ ! -f ../mpguard.ks ]; then
  keytool -genkeypair -keystore ../mpguard.ks -storepass mpguard123 -keypass mpguard123 \
    -alias mpguard -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=MpGuard, OU=local, O=local, L=local, S=local, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks ../mpguard.ks --ks-pass pass:mpguard123 \
  --key-pass pass:mpguard123 --out ../mp-guard.apk aligned.apk

cd "$APP"
echo "== 完成 =="
ls -la mp-guard.apk
"$BT/apksigner.bat" verify --print-certs mp-guard.apk 2>&1 | head -4
