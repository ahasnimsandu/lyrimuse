#!/usr/bin/env bash
# Installs the APK on the connected phone, grants the permissions the app needs
# and dumps what it is reading / matching, plus a screenshot.
#
# Usage: tools/verify-device.sh [apk] [seconds-to-watch]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/Lyriu.apk}"
WATCH="${2:-20}"
PKG="me.lyrimuse.android"

echo "== device =="
adb devices -l
adb wait-for-device

echo "== install =="
adb install -r "$APK" || { echo "install failed"; exit 1; }

echo "== permissions =="
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener "$PKG/$PKG.MediaListenerService" 2>/dev/null
adb shell cmd notification get_enabled_listeners 2>/dev/null | grep -o "$PKG[^ ]*" | head -2

echo "== start =="
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 3

echo "== media sessions on device =="
adb shell dumpsys media_session 2>/dev/null | grep -E "^ *package=|state=PlaybackState\{state=" | head -20

echo "== waiting ${WATCH}s for playback/matching =="
sleep "$WATCH"

echo "== app log =="
adb logcat -d -s LyrimusePlayer LyrimuseRepo AndroidRuntime | tail -40

echo "== overlay window present? =="
adb shell dumpsys window windows 2>/dev/null | grep -iE "OverlayView|me.lyrimuse" | head -5

echo "== screenshot =="
adb exec-out screencap -p > /tmp/lyrimuse-device.png && ls -lh /tmp/lyrimuse-device.png
