#!/usr/bin/env bash
set -euo pipefail
mkdir -p navigation-soak-results
pkg=com.drivemate.android.mapzoom
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
# Only synthetic fixtures are used. No real trips or crash payloads are uploaded.
adb shell am instrument -w -r -e soak true -e class com.drivemate.app.DmNavigationSessionTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee navigation-soak-results/soak.txt
grep -F 'OK (1 test)' navigation-soak-results/soak.txt
adb logcat -d -s OfflineRoutingPilot:I | grep -F 'Integrated session:' | tail -n 1 > navigation-soak-results/timing.txt
test -s navigation-soak-results/timing.txt
# Real UI at larger text scale; restore emulator defaults after execution.
old_scale="$(adb shell settings get system font_scale | tr -d '\r')"
trap 'if [[ "$old_scale" == null ]]; then adb shell settings delete system font_scale >/dev/null; else adb shell settings put system font_scale "$old_scale"; fi' EXIT
adb shell settings put system font_scale 1.3
bash tools/run_drivemate_replay.sh
# Aggregate emulator memory/CPU observations, not physical-device battery measurements.
adb shell dumpsys meminfo "$pkg" | grep -E 'TOTAL PSS:|TOTAL RSS:|TOTAL SWAP PSS:|TOTAL[[:space:]]+[0-9]' > navigation-soak-results/memory.txt || true
adb shell dumpsys cpuinfo | grep -F "$pkg" > navigation-soak-results/cpu.txt || true
printf 'Synthetic 3600-fix session and 1.3x font UI replay. Emulator metrics are observational, not battery or accessibility certification.\n' > navigation-soak-results/limitations.txt
