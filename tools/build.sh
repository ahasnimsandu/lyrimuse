#!/usr/bin/env bash
# Builds Lyrimuse for Android without Gradle:
#   aapt2 (resources) -> javac -> d8 (dex) -> zip -> apksig (v1/v2/v3)
#
# Requires: JDK 17, android.jar (platform 33+), aapt2, d8, apksig.jar.
# Override with env vars: ANDROID_JAR, AAPT2, D8, APKSIG_JAR, KEYSTORE.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
AAPT2="${AAPT2:-$SDK/build-tools/34.0.0/aapt2}"
D8="${D8:-d8}"
APKSIG_JAR="${APKSIG_JAR:-$ROOT/tools/apksig.jar}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
OUT="$ROOT/build"
APP="$ROOT/app/src/main"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/sign"

echo "[1/6] resources"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] link"
# AGP supplies the namespace from build.gradle, aapt2 needs it in the manifest,
# so build a temporary manifest with the package attribute injected.
python3 - "$APP/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
xml = open(src, encoding="utf-8").read()
marker = 'xmlns:android="http://schemas.android.com/apk/res/android"'
xml = xml.replace(marker, marker + ' package="me.lyrimuse.android"', 1)
open(dst, "w", encoding="utf-8").write(xml)
PY
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 1.0.0 \
  --auto-add-overlay

echo "[3/6] javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -nowarn -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] dex"
find "$OUT/classes" -name '*.class' -print0 | xargs -0 "$D8" \
  --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex"

echo "[5/6] package"
# Append classes.dex without recompressing: resources.arsc must stay STORED
# (uncompressed) and 4-byte aligned for targetSdk 30+.
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
with zipfile.ZipFile(apk) as z:
    arsc = z.getinfo("resources.arsc")
    print("resources.arsc stored=%s offset=%d aligned=%s" % (
        arsc.compress_type == zipfile.ZIP_STORED, arsc.header_offset,
        arsc.header_offset % 4 == 0))
PY

echo "[6/6] sign"
javac -nowarn -classpath "$APKSIG_JAR" -d "$OUT/sign" "$ROOT/tools/Sign.java"
java -classpath "$APKSIG_JAR:$OUT/sign" Sign \
  "$OUT/base.apk" "$ROOT/Lyrimuse.apk" "$KEYSTORE" android androiddebugkey

ls -lh "$ROOT/Lyrimuse.apk"
echo "APK ready: $ROOT/Lyrimuse.apk"
