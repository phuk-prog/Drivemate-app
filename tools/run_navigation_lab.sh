#!/usr/bin/env bash
set -euo pipefail
mkdir -p navigation-lab/evidence/drivemate
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
sleep 5
adb shell am instrument -w -r -e class com.drivemate.app.DmComparisonLabTest com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee navigation-lab/evidence/drivemate/instrumentation.txt
adb pull /sdcard/Android/data/com.drivemate.android.mapzoom/files/navigation-lab navigation-lab/evidence/drivemate/ || true
adb logcat -d -s NavigationLab:I > navigation-lab/evidence/drivemate/log.txt
adb shell am force-stop com.drivemate.android.mapzoom
python3 tools/run_navigation_lab.py
grep -F 'OK (1 test)' navigation-lab/evidence/drivemate/instrumentation.txt
