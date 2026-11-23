#!/usr/bin/env bash
# Emulator smoke test for the DriveMate debug build (see .github/workflows/ui-smoke.yml).
# Install, launch, confirm the app is still running and capture the screen.
set -euo pipefail

PKG="com.drivemate.android.mapzoom"   # debug build: applicationIdSuffix '.mapzoom'
ACTIVITY="com.drivemate.app.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"
OUT="ui-smoke"
mkdir -p "$OUT"

# -g grants the runtime permissions (location, notifications) up front.
adb install -r -g "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/$ACTIVITY"
sleep 15
adb exec-out screencap -p > "$OUT/first-screen.png"

adb logcat -d -b crash > "$OUT/crash.txt" || true
adb logcat -d > "$OUT/logcat.txt" || true

if ! adb shell pidof "$PKG" >/dev/null; then
  echo "DriveMate is not running 15 s after launch (crash on start?). See crash.txt." >&2
  exit 1
fi
if grep -q "com.drivemate" "$OUT/crash.txt"; then
  echo "A DriveMate crash was logged during start-up. See crash.txt." >&2
  exit 1
fi
echo "UI smoke test passed: DriveMate launched and is still running."
