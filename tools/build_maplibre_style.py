#!/usr/bin/env python3
"""Builds the GPU map styles (dm_gl_day.json, dm_gl_night.json) from the DriveMate vector styles.

One tile source ("dm") is fed by DriveMate's local tile server, which serves the best tile it has for each square:
installed map areas, then the OpenFreeMap CDN (OpenMapTiles schema), then the UK archive (Shortbread schema).
A style layer only draws tiles that contain its source-layer, so the style carries layers for both schemas and
each square is drawn by whichever set matches. Run after editing either input style:

    python3 tools/build_maplibre_style.py
"""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
FONT = ["Noto Sans Regular"]
NAME = ["coalesce", ["get", "name:en"], ["get", "name:latin"], ["get", "name"]]

PALETTE = {
    "day": dict(wood="#a9cba3", grass="#c8dfad", farm="#e9e3c9", resid="#e4dfd8", indus="#c8c7d2", water="#73b8d6",
                water_line="#73b8d6", building="#c5d0d2", building_line="#a8b7b9", casing="#f5f8f2", minor="#a6b2b8",
                service="#b8c4c6", motorway="#9b8cc5", trunk="#85b39a", primary="#c4ae85", path="#B9C2BE",
                rail="#9AA4AA", text="#203a4e", halo="#f1f6ea", place="#394E60", place_halo="#FFFFFF"),
    "night": dict(wood="#284b43", grass="#355b4b", farm="#3b453c", resid="#2d3e48", indus="#42465a", water="#205477",
                  water_line="#205477", building="#3b5267", building_line="#263b49", casing="#142330", minor="#607889",
                  service="#455967", motorway="#9b8cc5", trunk="#85b39a", primary="#c4ae85", path="#3E5463",
                  rail="#53687A", text="#e6f0f6", halo="#182a37", place="#D5E5EC", place_halo="#162B38"),
}

ROAD_WIDTH = {  # line-width stops (zoom, px) per OpenMapTiles class group, matched to the Shortbread layers
    "motorway": [6, 1.0, 10, 2.2, 12, 4.5, 14, 8.4, 16, 13, 18, 24, 19, 30],
    "main": [8, 0.6, 12, 3.4, 14, 7.0, 16, 11, 18, 20, 19, 26],
    "minor": [11, 0.5, 12, 1.6, 14, 5.6, 16, 8.5, 18, 15, 19, 20],
    "service": [14, 1.0, 16, 4, 18, 9, 19, 12],
}


def width(stops, extra=0.0):
    out = ["interpolate", ["linear"], ["zoom"]]
    for i in range(0, len(stops), 2):
        out += [stops[i], round(stops[i + 1] + (extra if stops[i + 1] > 0 else 0), 2)]
    return out


def omt_layers(p):
    is_ = lambda *classes: ["in", ["get", "class"], ["literal", list(classes)]]
    tunnel = ["==", ["get", "brunnel"], "tunnel"]
    opacity = ["case", tunnel, 0.45, 1]
    fills = [
        {"id": "omt-landcover", "type": "fill", "source": "dm", "source-layer": "landcover",
         "paint": {"fill-color": ["match", ["get", "class"], "wood", p["wood"], ["grass", "park"], p["grass"],
                                  "farmland", p["farm"], p["grass"]], "fill-opacity": 0.9}},
        {"id": "omt-landuse", "type": "fill", "source": "dm", "source-layer": "landuse", "minzoom": 9,
         "paint": {"fill-color": ["match", ["get", "class"], ["residential", "suburb", "neighbourhood"], p["resid"],
                                  ["industrial", "commercial", "retail", "railway"], p["indus"], p["resid"]],
                   "fill-opacity": 0.6}},
        {"id": "omt-park", "type": "fill", "source": "dm", "source-layer": "park", "paint": {"fill-color": p["grass"]}},
        {"id": "omt-water", "type": "fill", "source": "dm", "source-layer": "water", "paint": {"fill-color": p["water"]}},
        {"id": "omt-waterway", "type": "line", "source": "dm", "source-layer": "waterway", "minzoom": 10,
         "paint": {"line-color": p["water_line"], "line-width": width([10, 0.6, 14, 1.6, 18, 4])}},
    ]
    building = [{"id": "omt-building", "type": "fill", "source": "dm", "source-layer": "building", "minzoom": 13,
                 "paint": {"fill-color": p["building"], "fill-outline-color": p["building_line"]}}]
    building3d = [{"id": "omt-building-3d", "type": "fill-extrusion", "source": "dm", "source-layer": "building",
                   "minzoom": 14.5,
                   "paint": {"fill-extrusion-color": p["building"],
                             "fill-extrusion-height": ["interpolate", ["linear"], ["zoom"], 14.5, 0, 15.4,
                                                       ["coalesce", ["get", "render_height"], 6]],
                             "fill-extrusion-base": ["coalesce", ["get", "render_min_height"], 0],
                             "fill-extrusion-opacity": 0.88}}]
    groups = [("service", is_("service", "track"), p["service"], 14),
              ("minor", is_("minor"), p["minor"], 11),
              ("main", is_("primary", "secondary", "tertiary"), p["primary"], 8),
              ("main", is_("trunk"), p["trunk"], 6),
              ("motorway", is_("motorway"), p["motorway"], 5)]
    roads = [{"id": "omt-path", "type": "line", "source": "dm", "source-layer": "transportation", "minzoom": 15,
              "filter": is_("path"), "paint": {"line-color": p["path"], "line-dasharray": [2, 1.5],
                                               "line-width": width([15, 0.7, 18, 1.8, 19, 2.4])}},
             {"id": "omt-rail", "type": "line", "source": "dm", "source-layer": "transportation", "minzoom": 10,
              "filter": is_("rail", "transit"), "paint": {"line-color": p["rail"], "line-dasharray": [3, 2],
                                                         "line-width": width([10, 0.6, 14, 1.4, 18, 3])}}]
    layout = {"line-cap": "round", "line-join": "round"}
    for i, (key, flt, colour, minz) in enumerate(groups):   # all casings first: no dark rings where roads meet
        roads.append({"id": f"omt-casing-{i}", "type": "line", "source": "dm", "source-layer": "transportation",
                      "minzoom": max(minz, 11 if key != "motorway" else 7), "filter": flt, "layout": layout,
                      "paint": {"line-color": p["casing"], "line-width": width(ROAD_WIDTH[key], 1.6),
                                "line-opacity": opacity}})
    for i, (key, flt, colour, minz) in enumerate(groups):
        roads.append({"id": f"omt-road-{i}", "type": "line", "source": "dm", "source-layer": "transportation",
                      "minzoom": minz, "filter": flt, "layout": layout,
                      "paint": {"line-color": colour, "line-width": width(ROAD_WIDTH[key]), "line-opacity": opacity}})
    labels = [
        {"id": "omt-road-ref", "type": "symbol", "source": "dm", "source-layer": "transportation_name", "minzoom": 9,
         "filter": ["all", ["has", "ref"], is_("motorway", "trunk", "primary")],
         "layout": {"text-field": ["get", "ref"], "text-font": FONT, "text-size": 11, "symbol-placement": "line",
                    "symbol-spacing": 400, "text-rotation-alignment": "viewport"},
         "paint": {"text-color": "#ffffff", "text-halo-color": p["motorway"], "text-halo-width": 3}},
        {"id": "omt-road-names", "type": "symbol", "source": "dm", "source-layer": "transportation_name",
         "minzoom": 13, "filter": ["has", "name"],
         "layout": {"text-field": NAME, "text-font": FONT, "symbol-placement": "line", "text-max-angle": 30,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10, 17, 13, 19, 15]},
         "paint": {"text-color": p["text"], "text-halo-color": p["halo"], "text-halo-width": 1.9}},
        {"id": "omt-water-names", "type": "symbol", "source": "dm", "source-layer": "water_name", "minzoom": 12,
         "layout": {"text-field": NAME, "text-font": FONT, "text-size": 11},
         "paint": {"text-color": "#2c6f92", "text-halo-color": p["halo"], "text-halo-width": 1.4}},
        {"id": "omt-housenumbers", "type": "symbol", "source": "dm", "source-layer": "housenumber", "minzoom": 17,
         "layout": {"text-field": ["get", "housenumber"], "text-font": FONT, "text-size": 10},
         "paint": {"text-color": p["text"], "text-halo-color": p["halo"], "text-halo-width": 1.2}},
        {"id": "omt-places", "type": "symbol", "source": "dm", "source-layer": "place",
         "filter": ["!", is_("country", "state", "continent")],
         "layout": {"text-field": NAME, "text-font": FONT, "text-padding": 6,
                    "symbol-sort-key": ["coalesce", ["get", "rank"], 10],
                    "text-size": ["match", ["get", "class"], "city", 16, "town", 14, ["village", "suburb"], 12, 11]},
         "paint": {"text-color": p["place"], "text-halo-color": p["place_halo"], "text-halo-width": 1.7}},
    ]
    return fills, building, building3d, roads, labels


def build(source_name, mode):
    style = json.loads((ASSETS / source_name).read_text())
    p = PALETTE[mode]
    style["name"] = f"DriveMate GPU map ({mode})"
    uk = style["sources"].pop("uk")
    uk.pop("bounds", None)
    uk["minzoom"] = 0
    style["sources"]["dm"] = uk
    layers = []
    for layer in style["layers"]:
        if "icon-image" in layer.get("layout", {}):
            continue  # no sprite sheet: icon layers would only log missing images
        layer = copy.deepcopy(layer)
        if layer.get("source") == "uk":
            layer["source"] = "dm"
        layers.append(layer)
    fills, building, building3d, roads, labels = omt_layers(p)

    def after(layer_id, extra):
        idx = next(i for i, l in enumerate(layers) if l["id"] == layer_id)
        layers[idx + 1:idx + 1] = extra

    after("uk-water", fills)
    after("uk-buildings", building)
    after("uk-bridge-road", roads)
    after("uk-buildings-3d", building3d)
    layers.extend(labels)
    style["layers"] = layers
    return style


def main():
    for source_name, mode, out in (("drivemate_vector_style.json", "day", "dm_gl_day.json"),
                                   ("drivemate_vector_style_night.json", "night", "dm_gl_night.json")):
        style = build(source_name, mode)
        text = json.dumps(style, indent=1, ensure_ascii=False) + "\n"
        assert "https://" not in text and "tomtom" not in text.lower()
        (ASSETS / out).write_text(text)
        print(f"{out}: {len(style['layers'])} layers")


if __name__ == "__main__":
    main()
