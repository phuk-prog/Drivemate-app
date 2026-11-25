#!/usr/bin/env bash
set -euo pipefail
mkdir -p navigation-lab/evidence/drivemate-ui
adb shell am force-stop com.drivemate.android.mapzoom
adb shell pm grant com.drivemate.android.mapzoom android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.drivemate.android.mapzoom android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.drivemate.android.mapzoom android.permission.POST_NOTIFICATIONS || true
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
adb shell am instrument -w -r -e class com.drivemate.app.DmDrivingUiLabTest com.drivemate.android.mapzoom.test/androidx.test.runner.AndroidJUnitRunner | tee navigation-lab/evidence/drivemate-ui/instrumentation.txt
grep -F 'OK (1 test)' navigation-lab/evidence/drivemate-ui/instrumentation.txt
adb pull /sdcard/Android/data/com.drivemate.android.mapzoom/files/navigation-ui-lab navigation-lab/evidence/drivemate-ui/
test -s navigation-lab/evidence/drivemate-ui/navigation-ui-lab/driving-ui-results.json
python3 - <<'PY'
import json
from pathlib import Path
p=Path("navigation-lab/evidence/drivemate-ui/navigation-ui-lab")
r=json.loads((p/"driving-ui-results.json").read_text())
assert r and any(x["active"] and x["next_instruction"] for x in r), "No active driving instruction captured"
assert list(p.glob("frame-*.png")), "No UI screenshot"
PY
