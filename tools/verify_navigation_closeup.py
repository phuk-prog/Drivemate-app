#!/usr/bin/env python3
"""Build-time gate for safe higher-detail UK navigation map rendering.

The pinned UK PMTiles archive physically ends at z14. This checker protects
that honest local offline fallback while validating the optional higher-zoom
TomTom raster source, day/night parity and approach-line contrast.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STYLES = ROOT / "app/src/main/assets"
day = json.loads((STYLES / "drivemate_vector_style.json").read_text())
night = json.loads((STYLES / "drivemate_vector_style_night.json").read_text())

def get_layer(style, name):
    return next(layer for layer in style["layers"] if layer["id"] == name)

assert day["sources"] == night["sources"], "Night/day sources drifted"
assert day["sources"]["uk"]["maxzoom"] == 14, "Do not pretend local archive has z15+"
assert day["sources"]["world"]["url"] == "pmtiles://asset://world-z0-6.pmtiles"
detail = day["sources"].get("tomtom-high-detail")
assert detail and detail["type"] == "raster"
assert detail["minzoom"] >= 16 and detail["maxzoom"] >= 19
assert "__TOMTOM_KEY__" in detail["tiles"][0], "No credentials in source"
assert "__TOMTOM_DETAIL_STYLE__" in detail["tiles"][0]

for style in (day, night):
    names = [layer["id"] for layer in style["layers"]]
    assert len(names) == len(set(names)), "Duplicate layers"
    assert "uk-roads" in names and "uk-street-labels" in names
    assert "uk-motorways" in names and "uk-slip-roads" in names
    assert "uk-buildings" in names
    assert names.index("tomtom-street-detail") > names.index("uk-street-labels")
    assert names.index("tomtom-street-detail") < names.index("tomtom-traffic-flow")
    overlay = get_layer(style, "tomtom-street-detail")
    assert overlay["minzoom"] >= 16
    assert overlay["layout"]["visibility"] == "none", "Key gate must enable online tiles"
    assert overlay["source"] == "tomtom-high-detail"
    for name in ("uk-road-casing", "uk-roads", "uk-trunk"):
        layer = get_layer(style, name)
        assert layer["source-layer"] == "streets"
        assert layer["paint"]["line-width"][0] == "interpolate"
    assert get_layer(style, "uk-street-labels")["source-layer"] == "street_labels"

assert {v["id"] for v in day["layers"]} == {v["id"] for v in night["layers"]}
assert get_layer(day, "uk-road-casing")["paint"]["line-color"] != (
       get_layer(night, "uk-road-casing")["paint"]["line-color"])

vector = (ROOT / "app/src/main/java/com/drivemate/app/VectorDriveMap.java").read_text()
assert '.replace("__TOMTOM_DETAIL_STYLE__"' in vector
assert "highDetailEnabled && !tomtomKey.isEmpty()" in vector
assert "dm-junction-halo" in vector
camera = (ROOT / "app/src/main/java/com/drivemate/app/NavigationCamera.java").read_text()
assert "boolean junctionDetailZoom" in camera
assert "followingJunctionOffsetMetres" in camera
print("PASS: valid online close-up map layer, honest z14 offline fallback, night/day parity and camera integration")
