#!/usr/bin/env python3
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
lock=json.loads((root/"release/map-lock.json").read_text())
def check(path, values):
    text=(root/path).read_text()
    if not all(v in text for v in values):raise SystemExit("Map lock mismatch: "+path)
check("tools/fetch_pinned_map.py",[lock["world"]["tag"],lock["world"]["sha256"]])
check("app/src/main/java/com/drivemate/app/LocalRoutingPack.java",[lock["ukRouting"]["tag"],lock["ukRouting"]["manifestSha256"]])
check("tools/prepare_offline_routing_pilot.py",[lock["pilotSourceCommit"],lock["pilotSourceSha256"],lock["ukRouting"]["tag"],lock["ukRouting"]["manifestSha256"]])
print("Pinned map source identities match app, fixtures and release contract")
