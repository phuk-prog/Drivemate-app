#!/usr/bin/env python3
"""Native base contract: external providers may only contribute optional area data."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
src=root/"app/src/main/java/com/drivemate/app"
assets=root/"app/src/main/assets"
main=(src/"MainActivity.java").read_text()
native=(src/"NativeRegionalRouter.java").read_text()
assert "new NativeRegionalRouter(this).preview(start,end," in main   # native router, optionally with the travel bearing
assert "new NativeRegionalRouter(this).search(query,near)" in main
for forbidden in ("TomTomApi.route(","TomTomApi.search(","TomTomApi.parkingNearby(","DriveMateProvider.route(","DriveMateProvider.search(","DriveMateProvider.roadContext(","api.tomtom.com"):
    assert forbidden not in main, forbidden
assert "new NativeMapController(" in main
assert "org.osmdroid" not in main and "new VectorDriveMap(" not in main and "new MapView(" not in main
for name in ("NativeMapController.java","NativeMapView.java","NativeMvtDecoder.java","NativeMapScene.java","NativeTileSource.java","NativeMapProjection.java"):
    code=(src/name).read_text()
    assert "import org.osmdroid" not in code,name
assert "new DriveTileServer(app,false)" in (src/"NativeTileSource.java").read_text()
assert "TomTomApi.liveIncidents(key,p)" in main
assert "incident_enhancement_consent" in main and "NativeEnhancementPolicy.enabled" in main
assert "http://" not in native and "https://" not in native
for name in ("drivemate_vector_style.json","drivemate_vector_style_night.json","dm_unified_day.json","dm_unified_night.json","dm_gl_day.json","dm_gl_night.json"):
    style=json.loads((assets/name).read_text())
    assert "tomtom" not in json.dumps(style).lower(),name
    assert "https://" not in json.dumps(style),name
print("PASS: DriveMate-owned base routes/search/styles; external traffic area overlay only; no external fallback")

assert 'org.osmdroid:' not in (root/'app/build.gradle').read_text()
assert 'org.osmdroid:' not in (root/'app/gradle.lockfile').read_text()
assert not any('import org.osmdroid.' in p.read_text() for p in src.glob('*.java'))

# MapLibre (owner-approved 2.3.0) is a display engine only: no routing, search or provider adapters, and only
# MapLibreMapController may import it. Routing/guidance/search above stay DriveMate-owned.
for p in src.glob('*.java'):
    if p.name!='MapLibreMapController.java':
        assert 'import org.maplibre' not in p.read_text(),p.name
gl=(src/'MapLibreMapController.java').read_text()
for forbidden in ('TomTomApi.route(','TomTomApi.search(','https://'):
    assert forbidden not in gl,forbidden
assert 'new NativeMapController(this,mapHealth)' in main   # classic engine stays as the fallback
print("PASS: MapLibre is display-only; DriveMate classic renderer kept as fallback")
