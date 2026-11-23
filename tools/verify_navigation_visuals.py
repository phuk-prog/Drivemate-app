#!/usr/bin/env python3
"""Validate the supported local-overzoom + optional online road-detail design.

The pinned UK PMTiles archive really ends at zoom 14. We do NOT claim it has
z15-19 data. Online high-resolution tiles are a separate, source-backed layer.
"""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets"
paths = [root / "drivemate_vector_style.json",
         root / "drivemate_vector_style_night.json"]
styles = [json.loads(p.read_text()) for p in paths]
day, night = styles
assert day["sources"] == night["sources"], "Day/night must share source definitions"
assert day["sources"]["uk"]["maxzoom"] == 14, "Do not mislabel the real z14 UK data"
hi = day["sources"]["tomtom-high-detail"]
assert hi["type"] == "raster" and hi["minzoom"] == 16 and hi["maxzoom"] == 19
assert "__TOMTOM_STYLE__" in hi["tiles"][0]
assert "__TOMTOM_KEY__" in hi["tiles"][0]
assert "/map/1/tile/basic/" in hi["tiles"][0]

for style in styles:
    layers = style["layers"]
    names = [l["id"] for l in layers]
    assert len(names) == len(set(names)), "Duplicate layer name"
    assert names.index("uk-roads") < names.index("uk-trunk")
    assert names.index("uk-trunk") < names.index("uk-road-links")
    assert names.index("uk-road-links") < names.index("uk-street-labels")
    assert names.index("uk-street-labels") < names.index("dm-tomtom-high-detail")
    assert names.index("dm-tomtom-high-detail") < names.index("tomtom-traffic-flow")
    byid = {x["id"]: x for x in layers}
    assert set(["uk-roads", "uk-trunk", "uk-road-casing",
                "uk-road-links", "uk-road-link-casing",
                "uk-secondary-roads", "uk-street-labels",
                "dm-tomtom-high-detail"]).issubset(byid)
    high = byid["dm-tomtom-high-detail"]
    assert high["type"] == "raster"
    assert high["source"] == "tomtom-high-detail"
    assert high["layout"]["visibility"] == "none", "Opt-in must be controlled by code"
    assert high["minzoom"] >= 16
    for identifier in ["uk-roads", "uk-road-casing", "uk-trunk"]:
        points = byid[identifier]["paint"]["line-width"]
        assert points[0] == "interpolate"
        assert points[-2] == 19, "Road strokes should scale at maximum camera zoom"
        assert points[-1] > points[-3], "Road strokes must not shrink when zooming in"
    assert byid["uk-street-labels"]["layout"]["text-size"][-2] == 19
    for identifier in ["uk-secondary-roads", "uk-road-links"]:
        assert byid[identifier]["source-layer"] == "streets"
        assert byid[identifier]["source"] == "uk"
assert {x["id"] for x in day["layers"]} == {x["id"] for x in night["layers"]}
print("PASS: Day/night close-up map styles use true online z16-19 tiles over real local z14 data")
