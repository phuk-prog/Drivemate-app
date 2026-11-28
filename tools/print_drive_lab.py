#!/usr/bin/env python3
"""Prints drive-lab results and frames into the CI log so they can be reviewed without downloading artifacts.

Frames are base64 JPEGs between DRIVE-LAB-FRAME-BEGIN/END markers; tools/extract_drive_lab.py turns a saved
job log back into files.
"""
import base64
import json
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "unified-zoom-results/drive-lab")
for scenario in sorted(p for p in root.glob("*") if p.is_dir()):
    results = scenario / "results.json"
    if results.exists():
        data = json.loads(results.read_text())
        print(f"DRIVE-LAB-RESULTS-BEGIN {scenario.name}")
        print(json.dumps(data, separators=(",", ":")))
        print("DRIVE-LAB-RESULTS-END")
    for frame in sorted(scenario.glob("frame-*.jpg")):
        encoded = base64.b64encode(frame.read_bytes()).decode()
        print(f"DRIVE-LAB-FRAME-BEGIN {scenario.name}/{frame.name}")
        for i in range(0, len(encoded), 4000):
            print(encoded[i:i + 4000])
        print("DRIVE-LAB-FRAME-END")
