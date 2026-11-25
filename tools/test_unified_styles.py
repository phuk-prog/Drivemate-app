import json
from pathlib import Path
for name in ("day","night"):
    s=json.loads((Path("app/src/main/assets")/f"dm_unified_{name}.json").read_text())
    assert set(s["sources"])=={"unified-roads","unified-areas","unified-route"}
    assert all(v["maxzoom"]==21 and v["tolerance"]==0 for v in s["sources"].values())
    def zoom_count(v):
        return (int(v==["zoom"])+sum(zoom_count(x) for x in v)) if isinstance(v,list) else 0
    for layer in s["layers"]:
        for prop in layer.get("paint",{}).values():
            if zoom_count(prop):assert prop[0] in ("interpolate","step") and zoom_count(prop)==1 and ["zoom"] in prop,(layer["id"],prop)
    print("PASS shared",name,"map styles; no raster sources and top-level camera expressions")
