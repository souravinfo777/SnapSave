#!/usr/bin/env bash
# Host-only Java 8 regression tests. No Gradle, Android SDK, downloads or external jars.
# Usage: JAVA_HOME=/path/to/jdk bash tests/run-host-tests.sh
set -euo pipefail

TESTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$TESTS/.." && pwd)"

if [[ -n "${JAVA_HOME:-}" ]]; then
    JDK="$JAVA_HOME"
    if command -v cygpath >/dev/null 2>&1; then JDK="$(cygpath -u "$JDK")"; fi
    JAVA="$JDK/bin/java"
    JAVAC="$JDK/bin/javac"
    KEYTOOL="$JDK/bin/keytool"
else
    JAVA="$(command -v java || true)"
    JAVAC="$(command -v javac || true)"
    KEYTOOL="$(command -v keytool || true)"
fi
for tool in "$JAVA" "$JAVAC" "$KEYTOOL"; do
    if [[ -z "$tool" ]] || ! "$tool" -help >/dev/null 2>&1; then
        printf '%s\n' 'A JDK 8+ with java, javac and keytool is required. Set JAVA_HOME to that JDK.' >&2
        exit 1
    fi
done

# Each invocation is isolated, even when several agents run tests concurrently.
BUILD="$(mktemp -d "$TESTS/.host-run.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes" "$BUILD/empty-sourcepath" "$BUILD/work"

# An explicit allowlist prevents javac from discovering Android sources or real network backends.
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
    -sourcepath "$BUILD/empty-sourcepath" -classpath "$BUILD/classes" -d "$BUILD/classes" \
    "$PROJECT/src/com/snapsave/app/model/StreamOption.java" \
    "$PROJECT/src/com/snapsave/app/engine/DownloadTask.java" \
    "$PROJECT/src/com/snapsave/app/engine/ResolvedMedia.java" \
    "$PROJECT/src/com/snapsave/app/engine/MediaUrls.java" \
    "$PROJECT/src/com/snapsave/app/engine/MediaTypes.java" \
    "$PROJECT/src/com/snapsave/app/engine/ResolveFailure.java" \
    "$PROJECT/src/com/snapsave/app/engine/Http.java" \
    "$PROJECT/src/com/snapsave/app/engine/TaskRefresh.java" \
    "$TESTS/stubs/com/snapsave/app/engine/Resolvers.java" \
    "$TESTS/stubs/com/snapsave/app/engine/FastSaver.java" \
    "$TESTS/HostRegression.java" \
    "$TESTS/ResolvedMediaValidityRegression.java"

# Fresh local TLS identity, trusted only by this test JVM; no checked-in secrets/certificates.
"$KEYTOOL" -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=localhost' -ext 'SAN=DNS:localhost,IP:127.0.0.1' \
    -storetype JKS -keystore "$BUILD/fixture.jks" -storepass host-tests-only \
    -keypass host-tests-only -noprompt >/dev/null 2>&1

"$JAVA" -ea -cp "$BUILD/classes" com.snapsave.app.engine.HostRegression \
    "$BUILD/work" "$BUILD/fixture.jks"

"$JAVA" -ea -cp "$BUILD/classes" com.snapsave.app.engine.ResolvedMediaValidityRegression
