#!/usr/bin/env python3
"""Build small real Wells OSM graph using the pinned DriveMate Map importer."""
import sys, json, hashlib, sqlite3, subprocess, urllib.request, xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REF="e4bd8c41a04bf17a319df01cb0ff9579a4393426"
SOURCE_SHA="8cde16000a69096b62f7a75598fb892c2d8eea34b8f2237278a785ddf125ce26"
work=ROOT/"offline-pilot-work";work.mkdir(exist_ok=True)
for path in ("tools/build_uk_roads.py","tools/verify_uk_graph.py","osm/wells-places-2026-11-19.osm"):
    url=f"https://raw.githubusercontent.com/phuk-prog/Drivemate-map/{REF}/{path}"
    with urllib.request.urlopen(url,timeout=60) as response:
        content=response.read(20_000_000)
    (work/Path(path).name).write_bytes(content)
source=work/"wells-places-2026-11-19.osm"
if hashlib.sha256(source.read_bytes()).hexdigest()!=SOURCE_SHA:raise SystemExit("GitHub map hash mismatch")
sys.path.insert(0,str(work))
from build_uk_roads import Builder,digest
from verify_uk_graph import OfflineRoads
out=ROOT/"app/src/androidTest/assets";out.mkdir(parents=True,exist_ok=True)
db=out/"wells-routing.sqlite";db.unlink(missing_ok=True)
b=Builder(db);root=ET.parse(source).getroot()
tags=lambda o:{t.attrib["k"]:t.attrib["v"] for t in o.findall("tag")}
nodes={}
for n in root.findall("node"):
    i=int(n.attrib["id"]);lat=float(n.attrib["lat"]);lon=float(n.attrib["lon"])
    nodes[i]=(i,lat,lon);b.node(i,lat,lon,tags(n))
for w in root.findall("way"):
    refs=[int(n.attrib["ref"]) for n in w.findall("nd")]
    if all(i in nodes for i in refs):b.way(int(w.attrib["id"]),tags(w),[nodes[i] for i in refs])
for r in root.findall("relation"):
    members=[(m.attrib["role"],m.attrib["type"][0],int(m.attrib["ref"])) for m in r.findall("member")]
    b.turn(tags(r),members)
counts=b.finish("wells-pilot",SOURCE_SHA,min_edges=500)
roads=OfflineRoads(db)
# Fixed anchors around Wells; only compare completed reference journeys.
anchors=[(51.209,-2.647),(51.211,-2.652),(51.207,-2.641),(51.214,-2.646),(51.204,-2.650)]
samples=[]
for start,end in zip(anchors,anchors[1:]):
    r=roads.route(start,end,max_expansions=80000,max_seconds=20)
    if r["ok"] and r["distance_km"]>0.15:samples.append({"start":start,"end":end,"reference":r})
roads.close()
if len(samples)<2:raise SystemExit("Insufficient connected pilot journeys")
report={"source_sha256":SOURCE_SHA,"source_ref":REF,"database_sha256":digest(db),"database_bytes":db.stat().st_size,"counts":counts,"samples":samples,"road_validated":False}
(out/"wells-routing.json").write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))


# Verify the exact currently published routing manifest before Android CI.
url="https://github.com/phuk-prog/Drivemate-map/releases/download/routes-20261124T044450Z-4f30f06120b2-ba57998035b63/uk-routing-manifest.json"
with urllib.request.urlopen(url,timeout=60) as response: raw=response.read(65536)
assert hashlib.sha256(raw).hexdigest()=="19e668dc43d0abdc09e22af2a09672ac0d5dc6b8b12dd39b2b1c74d3a97f5b51"
manifest=json.loads(raw);assert manifest["format"]=="drivemate-uk-routing-v1"
(out/"published-routing-manifest.json").write_bytes(raw)
print("Verified actual pinned UK routing manifest; raw database bytes:",manifest["database_bytes"],"compressed bytes:",manifest["gzip_bytes"])
