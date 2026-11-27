#!/usr/bin/env python3
import hashlib,subprocess,sys,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REF="cf386ab2f4f0001801e7381b5948194b709c1fb3"  # Drivemate-map PR #30: routing graph keeps roads with non-car :conditional tags
work=ROOT/"stockport-unified-work";work.mkdir(exist_ok=True)
for name in ("prepare_stockport_unified.py","compile_dmtiles.py","verify_unified_geometry.py","build_uk_roads.py","build_traffic_signals.py","test_traffic_signals.py","build_roadside_points.py","test_roadside_points.py"):
    with urllib.request.urlopen(f"https://raw.githubusercontent.com/phuk-prog/Drivemate-map/{REF}/tools/{name}",timeout=60) as r:data=r.read(2_000_001)
    if len(data)>2_000_000:raise ValueError("Oversized pinned compiler")
    (work/name).write_bytes(data)
out=ROOT/"app/src/main/assets/dmtiles-stockport";out.mkdir(parents=True,exist_ok=True)
subprocess.run([sys.executable,str(work/"test_traffic_signals.py")],check=True)
subprocess.run([sys.executable,str(work/"test_roadside_points.py")],check=True)
signals=ROOT/"app/src/main/assets/uk-traffic-signals.bin"
roadside=ROOT/"app/src/main/assets/uk-roadside-points.bin"
subprocess.run([sys.executable,str(work/"prepare_stockport_unified.py"),"--out",str(out),"--signals-out",str(signals),"--roadside-out",str(roadside)],check=True)
if not 9040<=roadside.stat().st_size<=18000040:raise ValueError("Roadside asset size outside bounds")
if not 8040<=signals.stat().st_size<=8000040:raise ValueError("Signal asset size outside bounds")
(out/"manifest.sha256").write_text(hashlib.sha256((out/"manifest.json").read_bytes()).hexdigest()+"\n")
for tile in out.glob("*.dmt.gz"):tile.rename(tile.with_name(tile.name+".bin"))
size=sum(p.stat().st_size for p in out.iterdir() if p.is_file())
if size>20_000_000:raise ValueError("Regional APK addition exceeds 20 MB budget")
print("Stockport shared geometry packaged bytes",size,flush=True)
