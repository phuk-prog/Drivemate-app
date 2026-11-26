#!/usr/bin/env bash
set -euo pipefail
mkdir -p offline-pilot-results
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
sleep 5
adb shell dumpsys connectivity > offline-pilot-results/connectivity.txt
adb shell am instrument -w -r -e class com.drivemate.app.OfflineRoutingPilotTest,com.drivemate.app.PublishedRoutingManifestTest,com.drivemate.app.DmTileEngineTest com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee offline-pilot-results/instrumentation.txt
adb logcat -d -s OfflineRoutingPilot:I > offline-pilot-results/route-timings.txt
grep -F 'OK (3 tests)' offline-pilot-results/instrumentation.txt
