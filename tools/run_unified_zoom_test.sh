#!/usr/bin/env bash
set -euo pipefail
mkdir -p unified-zoom-results
# Preserve diagnostics even if the renderer never becomes ready.
trap 'adb logcat -d -s UnifiedRegion UnifiedZoom AndroidRuntime DEBUG libc DriveMate > unified-zoom-results/render-logcat.txt || true; echo "--- map engine log ---"; adb logcat -d | grep -iE "mbgl|maplibre|DriveMate|EngineComparison|AndroidRuntime|THREAD_STATE|GLSurfaceView|EGL" | grep -v Mbgl-HttpRequest | tail -300 || true' EXIT
: > unified-zoom-results/instrumentation.txt
for method in searchTypingAndPickingDoNotCrash mapLibreGpuMapDrawsRoadsAndRoute actualStockportGpuRoadsAndRouteAtEveryZoom actualLiveVectorRendererUsesSharedRoadsAtDrivingZoom nativeRegionalRouteAndSearchWithoutProvider; do
  adb shell am force-stop com.drivemate.android.mapzoom
  adb shell am instrument -w -r -e class "com.drivemate.app.DmUnifiedZoomTest#$method" com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee "unified-zoom-results/$method.txt"
  cat "unified-zoom-results/$method.txt" >> unified-zoom-results/instrumentation.txt
  grep -F 'OK (1 test)' "unified-zoom-results/$method.txt"
done
adb shell am force-stop com.drivemate.android.mapzoom
adb shell am instrument -w -r -e class com.drivemate.app.NativeMapRendererDeviceTest com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee unified-zoom-results/native-renderer.txt
grep -F 'OK (1 test)' unified-zoom-results/native-renderer.txt
adb shell am force-stop com.drivemate.android.mapzoom
adb shell am instrument -w -r -e class com.drivemate.app.NativeMainMapDeviceTest com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee unified-zoom-results/native-main-map.txt
grep -F 'OK (1 test)' unified-zoom-results/native-main-map.txt
# Same drive on every engine, fresh app each time. RESULT lines are the comparison (see EngineComparisonTest).
for engine in maplibre gpu classic; do
  adb shell am force-stop com.drivemate.android.mapzoom
  adb shell am instrument -w -r -e class "com.drivemate.app.EngineComparisonTest#$engine" com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee "unified-zoom-results/engine-$engine.txt"
  grep -F 'OK (1 test)' "unified-zoom-results/engine-$engine.txt"
done
adb logcat -d -s EngineComparison | grep RESULT || true
adb pull /sdcard/Android/data/com.drivemate.android.mapzoom/files/unified-zoom unified-zoom-results/ || true
