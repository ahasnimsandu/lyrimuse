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
KEYSTORE="${KEYSTORE:-$ROOT/tools/lyrimuse-signing.jks}"
KEYSTORE_PASS="${KEYSTORE_PASS:-lyrimuse}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-lyrimuse}"
OUT="$ROOT/build"
APP="$ROOT/app/src/main"

# Version follows an exact android-v* tag (that is what CI builds); local builds fall
# back to version.txt. versionCode is derived from the version so 1.7.0 -> 10700, which
# keeps every release a valid upgrade of the old 1..7 codes.
VERSION_NAME="${VERSION_NAME:-}"
EXACT_TAG="$(git -C "$ROOT" describe --tags --exact-match --match 'android-v*' 2>/dev/null || true)"
if [ -z "$VERSION_NAME" ] && [ -n "$EXACT_TAG" ]; then
  VERSION_NAME="${EXACT_TAG#android-v}"
fi
if [ -z "$VERSION_NAME" ] && [ -f "$ROOT/version.txt" ]; then
  VERSION_NAME="$(head -1 "$ROOT/version.txt" | tr -d '[:space:]')"
fi
VERSION_NAME="${VERSION_NAME:-1.7.0}"
if [ -z "${VERSION_CODE:-}" ]; then
  V_MAJOR="$(echo "$VERSION_NAME" | cut -d. -f1)"
  V_MINOR="$(echo "$VERSION_NAME" | cut -d. -f2)"
  V_PATCH="$(echo "$VERSION_NAME" | cut -d. -f3 | cut -d- -f1)"
  V_MAJOR="${V_MAJOR:-0}"; V_MINOR="${V_MINOR:-0}"; V_PATCH="${V_PATCH:-0}"
  VERSION_CODE=$((V_MAJOR * 10000 + V_MINOR * 100 + V_PATCH))
fi
echo "[0/6] version $VERSION_NAME ($VERSION_CODE)${EXACT_TAG:+ from tag $EXACT_TAG}"

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
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "[3/6] javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -nowarn -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] dex"
find "$OUT/classes" -name '*.class' -print0 | xargs -0 "$D8" \
  --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex"

echo "[5/6] package"
python3 "$ROOT/tools/package.py" "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk"

echo "[6/6] sign"
javac -nowarn -classpath "$APKSIG_JAR" -d "$OUT/sign" "$ROOT/tools/Sign.java"
java -classpath "$APKSIG_JAR:$OUT/sign" Sign \
  "$OUT/unsigned.apk" "$ROOT/Lyriu.apk" "$KEYSTORE" "$KEYSTORE_PASS" "$KEYSTORE_ALIAS"
python3 - "$ROOT/Lyriu.apk" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    a = z.getinfo("resources.arsc")
    print("signed apk: resources.arsc stored=%s aligned=%s" % (
        a.compress_type == zipfile.ZIP_STORED, a.header_offset % 4 == 0))
PY

ls -lh "$ROOT/Lyriu.apk"
echo "APK ready: $ROOT/Lyriu.apk"
