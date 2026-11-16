#!/usr/bin/env bash
# Manual Android build pipeline (no Gradle): aapt2 -> javac -> d8 -> package -> zipalign -> apksigner
set -euo pipefail

PROJ="D:/Website/SNAPTUBE REA/SnapSave"
BT="/d/android-sdk/build-tools/35.0.0"
AJAR="/d/android-sdk/platforms/android-35/android.jar"
cd "$PROJ"

echo "== clean =="
rm -rf build/compiled build/gen build/classes build/dex build/pkg build/*.apk build/sources.txt
mkdir -p build/compiled build/gen build/classes build/dex build/pkg

echo "== aapt2 compile =="
"$BT/aapt2.exe" compile --dir res -o build/compiled/res.zip

echo "== aapt2 link =="
"$BT/aapt2.exe" link -o build/resources.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version 21 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0 build/compiled/res.zip

echo "== javac =="
find src -name '*.java' > build/sources.txt
find build/gen -name 'R.java' >> build/sources.txt
wc -l < build/sources.txt
javac -nowarn -source 1.8 -target 1.8 -bootclasspath "$AJAR" -classpath "$AJAR" \
  -encoding UTF-8 -d build/classes @build/sources.txt

echo "== jar =="
jar cf build/classes.jar -C build/classes .

echo "== d8 =="
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 21 --lib "$AJAR" \
  --output build/dex build/classes.jar

echo "== package =="
( cd build && rm -rf pkg && mkdir pkg && cd pkg && unzip -q ../resources.apk )
cp build/dex/classes.dex build/pkg/classes.dex
mkdir -p build/pkg/lib
cp -r jniLibs/arm64-v8a build/pkg/lib/
cp -r jniLibs/armeabi-v7a build/pkg/lib/
if [ -d assets ] && [ -n "$(ls -A assets 2>/dev/null || true)" ]; then cp -r assets build/pkg/; fi
rm -f build/app-unsigned.apk
# STORE (no deflate) so resources.arsc stays uncompressed as required on Android 11+.
( cd build/pkg && jar cfM0 ../app-unsigned.apk . )

echo "== zipalign =="
"$BT/zipalign.exe" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "== keystore =="
if [ ! -f build/snapsave.keystore ]; then
  keytool -genkeypair -keystore build/snapsave.keystore -alias snapsave -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass snapsave123 -keypass snapsave123 \
    -dname "CN=SnapSave, OU=SnapSave, O=SnapSave, L=NA, ST=NA, C=US"
fi

echo "== sign =="
java -jar "$BT/lib/apksigner.jar" sign --ks build/snapsave.keystore --ks-pass pass:snapsave123 \
  --key-pass pass:snapsave123 --v1-signing-enabled true --v2-signing-enabled true \
  --out SnapSave.apk build/app-aligned.apk

echo "== verify =="
java -jar "$BT/lib/apksigner.jar" verify -v SnapSave.apk

echo "DONE: $PROJ/SnapSave.apk"
ls -la SnapSave.apk
