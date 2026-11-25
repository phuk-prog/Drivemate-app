#!/usr/bin/env python3
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"app/src/main/assets"
for file in ("drivemate_vector_style.json","drivemate_vector_style_night.json"):
    style=json.loads((root/file).read_text());layers=style["layers"];ids=[l["id"] for l in layers];assert len(ids)==len(set(ids))
    by={l["id"]:l for l in layers}
    assert by["uk-roads"]["source-layer"]=="streets"
    assert by["uk-buildings-3d"]["type"]=="fill-extrusion"
    assert by["uk-buildings-3d"]["minzoom"]==14.5
    assert ids.index("uk-roads")<ids.index("uk-ref-motorway")<ids.index("dm-tomtom-high-detail")
    assert by["uk-ref-motorway"]["filter"][1]==["has","ref"]
    assert by["uk-road-ref-fallback"]["filter"][1]==["has","ref"]
    assert style["sources"]["uk"]["maxzoom"]==14
    assert by["dm-tomtom-high-detail"]["layout"]["visibility"]=="none"
    assert by["tomtom-traffic-flow"]["layout"]["visibility"]=="none"
    expected={"ferries","street_labels_points","streets_polygons_labels","boundaries","boundary_labels","pier_lines","pier_polygons","dam_lines","dam_polygons","aerialways"}
    assert expected <= {l.get("source-layer") for l in layers}
    assert by["uk-motorway-junction-ref"]["source-layer"]=="street_labels_points"
    assert "link" in str(by["uk-road-links"]["filter"])
    assert "oneway_reverse" in str(by["uk-oneway-arrows"]["layout"])
    assert "kind" in str(by["uk-land"]["paint"]["fill-color"])
    print("PASS",file,len(ids),"layers, road classes, real ref-gated labels and building extrusion")

for file in ("dm_unified_day.json","dm_unified_night.json"):
    style=json.loads((root/file).read_text()); layers=style["layers"]; by={l["id"]:l for l in layers}
    assert len(by)==len(layers)
    assert style["sources"]["unified-points"]["tolerance"]==0
    assert "height_m" in str(by["buildings-3d"]["paint"])
    for id in ("regional-bridge-fill","regional-tunnel","regional-oneway","regional-driving-pois","regional-exits","regional-addresses","regional-entrances","regional-building-labels"):assert id in by
    print("PASS",file,"regional map detail contract")

for file in ("drivemate_vector_style.json","drivemate_vector_style_night.json"):
    s=json.loads((root/file).read_text());by={l["id"]:l for l in s["layers"]}
    for id in ("uk-road-casing","uk-roads","uk-road-links"):assert "bridge" in str(by[id]["filter"])
    assert by["tomtom-traffic-flow"]["layout"]["visibility"]=="none"
