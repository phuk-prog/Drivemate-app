#!/usr/bin/env python3
"""Compile pinned DriveMate Map source and independent tiles for Android QA."""
import hashlib,subprocess,sys,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REF="dca11035d8ff6990cd94e9b39f4d544524b9278d"
SHA="8cde16000a69096b62f7a75598fb892c2d8eea34b8f2237278a785ddf125ce26"
work=ROOT/"dmtiles-work";work.mkdir(exist_ok=True)
for path in ("tools/compile_dmtiles.py","tools/build_uk_roads.py","osm/wells-places-2026-11-19.osm"):
    with urllib.request.urlopen(f"https://raw.githubusercontent.com/phuk-prog/Drivemate-map/{REF}/{path}",timeout=60) as r: data=r.read(30_000_000)
    (work/Path(path).name).write_bytes(data)
out=ROOT/"app/src/main/assets/dmtiles"
out.mkdir(parents=True,exist_ok=True)
subprocess.run([sys.executable,str(work/"compile_dmtiles.py"),"--source",str(work/"wells-places-2026-11-19.osm"),"--sha256",SHA,"--out",str(out)],check=True)
(out/"manifest.sha256").write_text(hashlib.sha256((out/"manifest.json").read_bytes()).hexdigest()+"\n")

# Avoid Android asset packager special handling of .gz suffix.
for tile in out.glob("*.dmt.gz"):
    tile.replace(tile.with_name(tile.name+".bin"))
