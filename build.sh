#!/usr/bin/env bash
# Manual Android build pipeline (no Gradle): aapt2 -> javac -> d8 -> package -> zipalign -> apksigner
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
BT="${BT:-/d/android-sdk/build-tools/35.0.0}"
AJAR="${AJAR:-/d/android-sdk/platforms/android-35/android.jar}"
cd "$PROJ"

for f in "$BT/aapt2.exe" "$BT/zipalign.exe" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$AJAR"; do
  if [ ! -e "$f" ]; then
    echo "missing toolchain file: $f (set BT/AJAR)" >&2
    exit 1
  fi
done

# A release build must never ship the developer's local API keys or signing material.
if [ "${SNAPSAVE_RELEASE:-0}" = "1" ]; then
  if [ -f assets/apikeys.txt ]; then
    echo "refusing release build: assets/apikeys.txt contains local API keys" >&2
    exit 1
  fi
  # Release signing must come from the caller's environment: the throwaway key below is only
  # for local test builds, and a password committed in this script would be public forever.
  if [ -z "${SNAPSAVE_KEYSTORE:-}" ] || [ -z "${SNAPSAVE_STOREPASS:-}" ]; then
    echo "refusing release build: set SNAPSAVE_KEYSTORE and SNAPSAVE_STOREPASS" >&2
    exit 1
  fi
  if [ ! -f "$SNAPSAVE_KEYSTORE" ]; then
    echo "refusing release build: keystore not found: $SNAPSAVE_KEYSTORE" >&2
    exit 1
  fi
fi

echo "== clean =="
rm -rf build/compiled build/gen build/classes build/dex build/pkg
rm -f build/*.apk build/*.idsig build/classes.jar build/resources.apk build/sources.txt
mkdir -p build/compiled build/gen build/classes build/dex build/pkg

echo "== aapt2 compile =="
"$BT/aapt2.exe" compile --dir res -o build/compiled/res.zip

echo "== aapt2 link =="
# Version comes from AndroidManifest.xml: forcing code 1 / name 1.0 here made the packaged APK
# disagree with the manifest, which could be rejected as a downgrade on upgrade.
"$BT/aapt2.exe" link -o build/resources.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version 21 --target-sdk-version 35 \
  build/compiled/res.zip

echo "== javac =="
find src -name '*.java' | sort > build/sources.txt
find build/gen -name 'R.java' | sort >> build/sources.txt
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
# Belt and braces: never stage signing files, whatever the build type. Local builds still bundle
# the git-ignored assets/apikeys.txt (that is how a test phone gets its keys); a release build
# must not, and the guard below proves it did not.
find build/pkg -type f \( -name '*.keystore' -o -name '*.jks' -o -name '*.p12' -o -name '*.pfx' \) -delete

if [ "${SNAPSAVE_RELEASE:-0}" = "1" ]; then
  rm -f build/pkg/assets/apikeys.txt
  if [ -e build/pkg/assets/apikeys.txt ]; then
    echo "refusing release build: apikeys.txt reached the staged package" >&2
    exit 1
  fi
  if find build/pkg -type f \( -name '*.keystore' -o -name '*.jks' -o -name '*.p12' \) | grep -q .; then
    echo "refusing release build: signing material reached the staged package" >&2
    exit 1
  fi
fi

rm -f build/app-unsigned.apk
# STORE (no deflate) so resources.arsc stays uncompressed as required on Android 11+.
( cd build/pkg && jar cfM0 ../app-unsigned.apk . )

echo "== zipalign =="
"$BT/zipalign.exe" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "== keystore =="
if [ "${SNAPSAVE_RELEASE:-0}" = "1" ]; then
  KS="$SNAPSAVE_KEYSTORE"
  SP="$SNAPSAVE_STOREPASS"
  KP="${SNAPSAVE_KEYPASS:-$SNAPSAVE_STOREPASS}"
else
  # Local test key: generated once, never leaves build/ (git-ignored), never used for release.
  KS="build/snapsave.keystore"
  SP="snapsave123"
  KP="snapsave123"
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias snapsave -keyalg RSA -keysize 2048 \
      -validity 10000 -storepass "$SP" -keypass "$KP" \
      -dname "CN=SnapSave, OU=SnapSave, O=SnapSave, L=NA, ST=NA, C=US"
  fi
fi

echo "== sign =="
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass "pass:$SP" \
  --key-pass "pass:$KP" --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --out SnapSave.apk build/app-aligned.apk

echo "== verify =="
java -jar "$BT/lib/apksigner.jar" verify -v SnapSave.apk

echo "== badging =="
# The version must come from AndroidManifest.xml — printing it here catches a script override
# that would make the APK disagree with the manifest (and be rejected as a downgrade).
"$BT/aapt2.exe" dump badging SnapSave.apk | grep -E "^package:|^application-label:|^sdkVersion" || true

echo "== credential scan =="
if unzip -l SnapSave.apk | grep -Eiq 'apikeys|\.keystore|\.jks|\.p12|\.pfx'; then
  if [ "${SNAPSAVE_RELEASE:-0}" = "1" ]; then
    echo "refusing release build: credential material is inside the APK" >&2
    exit 1
  fi
  echo "note: local build bundles assets/apikeys.txt (git-ignored) — do not distribute this APK"
fi

echo "== sha256 =="
sha256sum SnapSave.apk

echo "DONE: $PROJ/SnapSave.apk"
ls -la SnapSave.apk
