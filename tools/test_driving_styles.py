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
    print("PASS",file,len(ids),"layers, road classes, real ref-gated labels and building extrusion")
