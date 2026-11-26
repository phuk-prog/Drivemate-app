#!/usr/bin/env bash
set -euo pipefail
mkdir -p unified-zoom-results
# Preserve diagnostics even if the renderer never becomes ready.
trap 'adb logcat -d -s UnifiedRegion UnifiedZoom AndroidRuntime > unified-zoom-results/render-logcat.txt || true' EXIT
: > unified-zoom-results/instrumentation.txt
for method in actualStockportGpuRoadsAndRouteAtEveryZoom actualLiveVectorRendererUsesSharedRoadsAtDrivingZoom; do
  adb shell am force-stop com.drivemate.android.mapzoom
  adb shell am instrument -w -r -e class "com.drivemate.app.DmUnifiedZoomTest#$method" com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee "unified-zoom-results/$method.txt"
  cat "unified-zoom-results/$method.txt" >> unified-zoom-results/instrumentation.txt
  grep -F 'OK (1 test)' "unified-zoom-results/$method.txt"
done
adb pull /sdcard/Android/data/com.drivemate.android.mapzoom/files/unified-zoom unified-zoom-results/ || true
