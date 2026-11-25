#!/usr/bin/env bash
set -euo pipefail
mkdir -p unified-zoom-results
adb shell am instrument -w -r -e class com.drivemate.app.DmUnifiedZoomTest com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee unified-zoom-results/instrumentation.txt
adb pull /sdcard/Android/data/com.drivemate.android.mapzoom/files/unified-zoom unified-zoom-results/ || true
grep -F 'OK (1 test)' unified-zoom-results/instrumentation.txt
