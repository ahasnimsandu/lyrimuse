#!/usr/bin/env bash
# Runs the plain-JVM lyric tests (no emulator, no Gradle).
# Requires ANDROID_JAR (platform 33+) and the committed tools/json.jar.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
JSON_JAR="${JSON_JAR:-$ROOT/tools/json.jar}"
SRC="$ROOT/app/src/main/java/me/lyrimuse/android"
OUT="$ROOT/build/test"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar not found at $ANDROID_JAR (set ANDROID_JAR)" >&2
  exit 1
fi
if [ ! -f "$JSON_JAR" ]; then
  echo "json.jar not found at $JSON_JAR" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

javac --release 11 -nowarn -classpath "$ANDROID_JAR:$JSON_JAR" -d "$OUT" \
  "$SRC/Lyrics.java" \
  "$SRC/Romanizer.java" \
  "$SRC/Fonts.java" \
  "$SRC/Prefs.java" \
  "$SRC/Cache.java" \
  "$ROOT/tools/test/TestLyrics.java"

java -classpath "$OUT:$ANDROID_JAR:$JSON_JAR" TestLyrics
