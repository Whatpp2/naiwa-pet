#!/bin/bash
# 手工构建奶蛙桌宠 APK（aarch64 容器 + x86_64 aapt2/zipalign 走 qemu）
set -e
SDK=/opt/android-sdk
BT=$SDK/build-tools/35.0.0
JDK=/opt/jdk17
QEMU=/tmp/qemuroot/usr/bin/qemu-x86_64-static
PROJ=/root/Documents/deepseek-harness/default-workspace/naiwa-pet
cd "$PROJ"

rm -rf build
mkdir -p build/compiled build/classes build/dex build/gen

echo "== aapt2 compile =="
$QEMU -L /tmp/x86root "$BT/aapt2" compile --dir res -o build/compiled/res.zip

echo "== aapt2 link =="
$QEMU -L /tmp/x86root "$BT/aapt2" link -o build/app-unsigned.apk \
  -I "$SDK/platforms/android-35/android.jar" \
  --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 21 --target-sdk-version 35 \
  -A assets \
  build/compiled/res.zip

echo "== javac =="
"$JDK/bin/javac" -source 17 -target 17 \
  -encoding UTF-8 -classpath "$SDK/platforms/android-35/android.jar" \
  -d build/classes \
  $(find src build/gen -name '*.java')

echo "== d8 =="
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 21 --lib "$SDK/platforms/android-35/android.jar" \
  --output build/dex \
  $(find build/classes -name '*.class')

echo "== add classes.dex =="
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/app-unsigned.apk', 'a') as z:
    z.write('build/dex/classes.dex', 'classes.dex')
PY

echo "== zipalign =="
$QEMU -L /tmp/x86root "$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "== keystore =="
if [ ! -f build/pet.keystore ]; then
  "$JDK/bin/keytool" -genkeypair -v -keystore build/pet.keystore -alias pet \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass naiwa123 -keypass naiwa123 \
    -dname "CN=Naiwa Pet, OU=DSH, O=DSH, L=Shanghai, ST=Shanghai, C=CN" \
    > /tmp/keytool.log 2>&1
fi

echo "== apksigner =="
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks build/pet.keystore --ks-pass pass:naiwa123 --key-pass pass:naiwa123 \
  --out build/naiwa-pet.apk build/app-aligned.apk

"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" verify build/naiwa-pet.apk
echo "BUILD OK"
ls -la build/naiwa-pet.apk
