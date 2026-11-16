#!/usr/bin/env bash
# Manual Android build pipeline (no Gradle): aapt2 -> javac -> d8 -> package -> zipalign -> apksigner
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
MIN_API=21
TARGET_API=35
RELEASE="${SNAPSAVE_RELEASE:-0}"
BUNDLE_LOCAL_KEYS="${SNAPSAVE_BUNDLE_LOCAL_KEYS:-0}"

# Git Bash accepts both /c/... and Windows-style C:\\... environment values. Keep all
# internally discovered paths in the shell's native form before invoking tools.
as_posix_path() {
  local value="${1:-}"
  if [[ "$value" =~ ^[A-Za-z]:[\\/].* ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath -u -- "$value"
  else
    printf '%s' "$value"
  fi
}

fail() {
  echo "build.sh: $*" >&2
  exit 1
}

# Resolve the JDK first so javac, jar, keytool, d8, and apksigner all use one installation.
JDK_HOME=""
if [ -n "${JAVA_HOME:-}" ]; then
  JDK_HOME="$(as_posix_path "$JAVA_HOME")"
  [ -d "$JDK_HOME" ] || fail "JAVA_HOME does not exist: $JAVA_HOME"
else
  jdk_candidates=()
  add_jdk_candidate() {
    local candidate="$1"
    local existing
    [ -d "$candidate" ] || return 0
    [ -f "$candidate/bin/java" ] || [ -f "$candidate/bin/java.exe" ] || return 0
    [ -f "$candidate/bin/javac" ] || [ -f "$candidate/bin/javac.exe" ] || return 0
    for existing in "${jdk_candidates[@]:-}"; do
      [ "$existing" = "$candidate" ] && return 0
    done
    jdk_candidates+=("$candidate")
  }

  for command_name in javac java; do
    command_path="$(command -v "$command_name" 2>/dev/null || true)"
    if [ -n "$command_path" ]; then
      command_path="$(as_posix_path "$command_path")"
      add_jdk_candidate "$(cd "$(dirname "$command_path")/.." && pwd)"
    fi
  done

  # Keep discovery bounded to conventional installation roots. JAVA_HOME remains the
  # override for a JDK installed elsewhere (including an extracted portable JDK archive).
  jdk_roots=(
    "$HOME/.jdks"
    "$HOME/.sdkman/candidates/java"
    "$PROJ/.jdks"
    "/c/Program Files/Java"
    "/c/Program Files/Eclipse Adoptium"
    "/c/Program Files/Microsoft"
    "/c/Program Files/Android/Android Studio/jbr"
    "/c/Program Files/Android/Android Studio/jre"
    "/c/Program Files (x86)/Java"
  )
  for root in "${jdk_roots[@]}"; do
    [ -d "$root" ] || continue
    for candidate in "$root" "$root"/* "$root"/*/*; do
      add_jdk_candidate "$candidate"
    done
  done

  # Choose the highest reported JDK version among the bounded candidates.
  best_version=""
  for candidate in "${jdk_candidates[@]:-}"; do
    candidate_java="$candidate/bin/java"
    [ -f "$candidate_java" ] || candidate_java="$candidate/bin/java.exe"
    version_line="$("$candidate_java" -version 2>&1 | while IFS= read -r line; do
      case "$line" in *version*) printf '%s' "$line"; break;; esac
    done)"
    candidate_version="${version_line#*\"}"
    candidate_version="${candidate_version%%\"*}"
    [ -n "$candidate_version" ] || continue
    newer=0
    if [ -z "$best_version" ]; then
      newer=1
    else
      sorted_versions=()
      mapfile -t sorted_versions < <(printf '%s\n' "$best_version" "$candidate_version" | sort -V)
      [ "${sorted_versions[${#sorted_versions[@]}-1]}" = "$candidate_version" ] && newer=1
    fi
    if [ "$newer" = "1" ]; then
      JDK_HOME="$candidate"
      best_version="$candidate_version"
    fi
  done
fi

[ -n "$JDK_HOME" ] || fail "JDK 17 or newer not found; set JAVA_HOME to a JDK installation"
JAVA="$JDK_HOME/bin/java"
JAVAC="$JDK_HOME/bin/javac"
JAR="$JDK_HOME/bin/jar"
KEYTOOL="$JDK_HOME/bin/keytool"
for tool in "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL"; do
  [ -f "$tool" ] || fail "JDK tool not found under JAVA_HOME: $tool"
done

java_version_line="$("$JAVA" -version 2>&1 | while IFS= read -r line; do printf '%s' "$line"; break; done)"
java_version="${java_version_line#*\"}"
java_version="${java_version%%\"*}"
java_major="${java_version%%.*}"
if [ "$java_major" = "1" ]; then
  java_major="${java_version#1.}"
  java_major="${java_major%%.*}"
fi
[ "$java_major" -ge 17 ] 2>/dev/null || fail "JDK 17 or newer is required (found $java_version)"

# SDK variables are explicit overrides. BT/AJAR are retained for callers that already pass
# individual toolchain paths; otherwise select the highest complete build-tools/platform pair.
BT="$(as_posix_path "${BT:-}")"
AJAR="$(as_posix_path "${AJAR:-}")"

sdk_candidates=()
if [ -n "${ANDROID_HOME:-}" ] || [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  [ -n "${ANDROID_HOME:-}" ] && sdk_candidates+=("$(as_posix_path "$ANDROID_HOME")")
  [ -n "${ANDROID_SDK_ROOT:-}" ] && sdk_candidates+=("$(as_posix_path "$ANDROID_SDK_ROOT")")
else
  [ -n "${HOME:-}" ] && sdk_candidates+=("$HOME/Android/Sdk")
  [ -n "${LOCALAPPDATA:-}" ] && sdk_candidates+=("$(as_posix_path "$LOCALAPPDATA")/Android/Sdk")
  sdk_candidates+=("/c/Users/$USER/AppData/Local/Android/Sdk" "/d/android-sdk")
fi

select_platform_jar() {
  local sdk="$1" dir version best_version=-1 best=""
  for dir in "$sdk"/platforms/android-*; do
    [ -d "$dir" ] || continue
    version="${dir##*/android-}"
    [[ "$version" =~ ^[0-9]+$ ]] || continue
    (( version >= TARGET_API )) || continue
    [ -f "$dir/android.jar" ] || continue
    if (( version > best_version )); then
      best_version="$version"
      best="$dir/android.jar"
    fi
  done
  if [ -n "$best" ]; then printf '%s' "$best"; fi
  return 0
}

select_build_tools() {
  local sdk="$1" dir
  build_tool_dirs=()
  for dir in "$sdk"/build-tools/*; do
    [ -d "$dir" ] || continue
    [ -f "$dir/lib/d8.jar" ] || continue
    [ -f "$dir/lib/apksigner.jar" ] || continue
    if { [ -f "$dir/aapt2" ] || [ -f "$dir/aapt2.exe" ]; } \
        && { [ -f "$dir/zipalign" ] || [ -f "$dir/zipalign.exe" ]; }; then
      build_tool_dirs+=("$dir")
    fi
  done
  [ "${#build_tool_dirs[@]}" -gt 0 ] || return 0
  ordered_build_tools=()
  mapfile -t ordered_build_tools < <(printf '%s\n' "${build_tool_dirs[@]}" | sort -V)
  printf '%s' "${ordered_build_tools[${#ordered_build_tools[@]}-1]}"
}

sdk_root=""
if [ -z "$BT" ] || [ -z "$AJAR" ]; then
  for sdk in "${sdk_candidates[@]}"; do
    [ -d "$sdk" ] || continue
    candidate_bt="$BT"
    candidate_ajar="$AJAR"
    [ -n "$candidate_bt" ] || candidate_bt="$(select_build_tools "$sdk")"
    [ -n "$candidate_ajar" ] || candidate_ajar="$(select_platform_jar "$sdk")"
    if [ -n "$candidate_bt" ] && [ -n "$candidate_ajar" ]; then
      BT="$candidate_bt"
      AJAR="$candidate_ajar"
      sdk_root="$sdk"
      break
    fi
  done
fi

[ -n "$BT" ] || fail "Android build-tools not found; set BT or ANDROID_HOME/ANDROID_SDK_ROOT"
[ -d "$BT" ] || fail "build-tools directory not found: $BT"
[ -n "$AJAR" ] || fail "Android platform android-$TARGET_API or newer not found; set AJAR"
[ -f "$AJAR" ] || fail "Android platform jar not found: $AJAR"

pick_tool() {
  local dir="$1" name="$2" candidate
  for candidate in "$dir/$name" "$dir/$name.exe"; do
    if [ -f "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}
AAPT2="$(pick_tool "$BT" aapt2)" || fail "aapt2 not found under $BT"
ZIPALIGN="$(pick_tool "$BT" zipalign)" || fail "zipalign not found under $BT"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
for file in "$D8_JAR" "$APKSIGNER_JAR"; do
  [ -f "$file" ] || fail "missing toolchain file: $file"
done

cd "$PROJ"
if [ "$RELEASE" = "1" ]; then
  [ "$BUNDLE_LOCAL_KEYS" != "1" ] || fail "SNAPSAVE_BUNDLE_LOCAL_KEYS=1 is local-only and cannot be used for a release build"
  if [ -f assets/apikeys.txt ]; then
    fail "refusing release build: assets/apikeys.txt contains local API keys"
  fi
  [ -n "${SNAPSAVE_KEYSTORE:-}" ] || fail "refusing release build: set SNAPSAVE_KEYSTORE and SNAPSAVE_STOREPASS"
  [ -n "${SNAPSAVE_STOREPASS:-}" ] || fail "refusing release build: set SNAPSAVE_KEYSTORE and SNAPSAVE_STOREPASS"
  SNAPSAVE_KEYSTORE_PATH="$(as_posix_path "$SNAPSAVE_KEYSTORE")"
  [ -f "$SNAPSAVE_KEYSTORE_PATH" ] || fail "refusing release build: keystore not found: $SNAPSAVE_KEYSTORE"
else
  if [ -f assets/apikeys.txt ] && [ "$BUNDLE_LOCAL_KEYS" != "1" ]; then
    echo "note: assets/apikeys.txt found; excluding it (set SNAPSAVE_BUNDLE_LOCAL_KEYS=1 for a local-only test APK)"
  fi
fi

printf '%s\n' "SDK tools: $BT" "Platform jar: $AJAR" "JDK: $JDK_HOME (Java $java_version)"

echo "== clean =="
rm -rf build/compiled build/gen build/classes build/dex build/pkg
rm -f build/*.apk build/*.idsig build/classes.jar build/resources.apk build/sources.txt
mkdir -p build/compiled build/gen build/classes build/dex build/pkg

echo "== aapt2 compile =="
"$AAPT2" compile --dir res -o build/compiled/res.zip

echo "== aapt2 link =="
# The target remains API 35 even when a newer installed android.jar is selected.
"$AAPT2" link -o build/resources.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version "$MIN_API" --target-sdk-version "$TARGET_API" \
  build/compiled/res.zip

echo "== javac =="
find src -name '*.java' | sort > build/sources.txt
find build/gen -name 'R.java' | sort >> build/sources.txt
wc -l < build/sources.txt
"$JAVAC" -nowarn -source 1.8 -target 1.8 -bootclasspath "$AJAR" -classpath "$AJAR" \
  -encoding UTF-8 -d build/classes @build/sources.txt

echo "== jar =="
"$JAR" cf build/classes.jar -C build/classes .

echo "== d8 =="
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api "$MIN_API" --lib "$AJAR" \
  --output build/dex build/classes.jar

echo "== package =="
( cd build && rm -rf pkg && mkdir pkg && cd pkg && unzip -q ../resources.apk )
cp build/dex/classes.dex build/pkg/classes.dex
mkdir -p build/pkg/lib
cp -r jniLibs/arm64-v8a build/pkg/lib/
cp -r jniLibs/armeabi-v7a build/pkg/lib/

# Copy app assets while excluding API keys by default. The opt-in is intentionally rejected
# for release builds above; release packages therefore cannot contain local API keys.
if [ -d assets ]; then
  mkdir -p build/pkg/assets
  shopt -s nullglob dotglob
  for asset in assets/*; do
    asset_name="${asset##*/}"
    if [ "$asset_name" = "apikeys.txt" ] && [ "$BUNDLE_LOCAL_KEYS" != "1" ]; then
      continue
    fi
    case "$asset_name" in
      *.keystore|*.jks|*.p12|*.pfx) continue ;;
    esac
    cp -r -- "$asset" build/pkg/assets/
  done
  shopt -u dotglob nullglob
fi
# Belt and braces: never stage signing files, whatever the build type.
find build/pkg -type f \( -name '*.keystore' -o -name '*.jks' -o -name '*.p12' -o -name '*.pfx' \) -delete

if [ "$RELEASE" = "1" ]; then
  if [ -e build/pkg/assets/apikeys.txt ]; then
    fail "refusing release build: API keys reached the staged package"
  fi
  if find build/pkg -type f \( -name '*.keystore' -o -name '*.jks' -o -name '*.p12' -o -name '*.pfx' \) | grep -q .; then
    fail "refusing release build: signing material reached the staged package"
  fi
fi

rm -f build/app-unsigned.apk
# STORE (no deflate) so resources.arsc and native libraries remain uncompressed.
( cd build/pkg && "$JAR" cfM0 ../app-unsigned.apk . )

echo "== zipalign =="
# Build-tools 36.1.0 supports -P 16, which aligns uncompressed native-library payloads for
# 16 KiB page devices while retaining the required four-byte APK entry alignment.
ZIPALIGN_HELP="$("$ZIPALIGN" -h 2>&1 || true)"
if [[ "$ZIPALIGN_HELP" == *'-P <pagesize_kb>'* ]]; then
  ZIPALIGN_16K=1
  "$ZIPALIGN" -P 16 -f 4 build/app-unsigned.apk build/app-aligned.apk
else
  ZIPALIGN_16K=0
  echo "warning: zipalign lacks -P 16; using legacy -p 4 alignment" >&2
  "$ZIPALIGN" -p -f 4 build/app-unsigned.apk build/app-aligned.apk
fi
if [ "$ZIPALIGN_16K" = "1" ]; then
  "$ZIPALIGN" -c -P 16 -v 4 build/app-aligned.apk >/dev/null
else
  "$ZIPALIGN" -c -v 4 build/app-aligned.apk >/dev/null
fi

echo "== keystore =="
if [ "$RELEASE" = "1" ]; then
  KS="$SNAPSAVE_KEYSTORE_PATH"
  SP="$SNAPSAVE_STOREPASS"
  KP="${SNAPSAVE_KEYPASS:-$SNAPSAVE_STOREPASS}"
else
  # Local test key: generated once, never leaves build/ (git-ignored), never used for release.
  KS="build/snapsave.keystore"
  SP="snapsave123"
  KP="snapsave123"
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -alias snapsave -keyalg RSA -keysize 2048 \
      -validity 10000 -storepass "$SP" -keypass "$KP" \
      -dname "CN=SnapSave, OU=SnapSave, O=SnapSave, L=NA, ST=NA, C=US"
  fi
fi

echo "== sign =="
"$JAVA" -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-pass "pass:$SP" \
  --key-pass "pass:$KP" --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --out SnapSave.apk build/app-aligned.apk

echo "== verify =="
"$JAVA" -jar "$APKSIGNER_JAR" verify -v SnapSave.apk

echo "== badging =="
# The version must come from AndroidManifest.xml; this catches script/package drift.
"$AAPT2" dump badging SnapSave.apk | grep -E "^package:|^application-label:|^sdkVersion" || true

echo "== credential scan =="
if unzip -l SnapSave.apk | grep -Eiq 'apikeys|\.keystore|\.jks|\.p12|\.pfx'; then
  if [ "$RELEASE" = "1" ] || [ "$BUNDLE_LOCAL_KEYS" != "1" ]; then
    fail "credential material is inside the APK"
  fi
  echo "note: local build bundles assets/apikeys.txt by explicit opt-in; do not distribute this APK"
fi

echo "== sha256 =="
sha256sum SnapSave.apk

echo "DONE: $PROJ/SnapSave.apk"
ls -la SnapSave.apk
