#!/usr/bin/env bash
# Runs the natives unit tests against the bundled libdeflate for this machine, without Gradle or
# Minecraft. CI runs it on every platform we ship.
#
# Needs JDK 25 on PATH (or JAVA_BIN=/path/to/jdk/bin/). Downloads the JUnit console launcher
# unless JUNIT_JAR points to one.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_BIN="${JAVA_BIN:-}"
JUNIT_VERSION="${JUNIT_VERSION:-1.14.4}"
OUT=build/natives-test
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

mkdir -p "$OUT"
JU="${JUNIT_JAR:-$OUT/junit-platform-console-standalone-$JUNIT_VERSION.jar}"
if [ ! -f "$JU" ]; then
  curl -fsSL -o "$JU" \
    "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"
fi

"${JAVA_BIN}java" -version 2>&1 | head -1
rm -rf "$OUT/main" "$OUT/test"
# shellcheck disable=SC2046
"${JAVA_BIN}javac" --release 25 -nowarn -d "$OUT/main" $(find common/src/main/java/io/github/ragamuffincat/swiftload/natives -name '*.java')
# shellcheck disable=SC2046
"${JAVA_BIN}javac" --release 25 -nowarn -cp "$OUT/main${SEP}$JU" -d "$OUT/test" $(find common/src/test/java/io/github/ragamuffincat/swiftload/natives -name '*.java')

"${JAVA_BIN}java" --enable-native-access=ALL-UNNAMED -jar "$JU" execute \
  --class-path "$OUT/main${SEP}$OUT/test${SEP}common/src/main/resources" \
  --select-package io.github.ragamuffincat.swiftload.natives --disable-banner --details=summary --fail-if-no-tests
