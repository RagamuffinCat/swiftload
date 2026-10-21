#!/usr/bin/env bash
# Compiles NativeBridge.java into the resource NativeBridge.bin, which isn't a .class file so no
# loader puts it in the mod's module. Re-run after changing it; BridgeSyncTest checks they match.
# Needs JDK 25 (javac on PATH or in $JAVA_HOME).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$JAVAC" --release 25 -d "$OUT" "$ROOT/common/src/bridge/java/io/github/ragamuffincat/swiftload/natives/bridge/NativeBridge.java"
DEST="$ROOT/common/src/main/resources/io/github/ragamuffincat/swiftload/natives/NativeBridge.bin"
mkdir -p "$(dirname "$DEST")"
cp "$OUT/io/github/ragamuffincat/swiftload/natives/bridge/NativeBridge.class" "$DEST"
echo "wrote $DEST ($(wc -c < "$DEST") bytes)"
