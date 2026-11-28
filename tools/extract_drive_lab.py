#!/usr/bin/env python3
"""Rebuilds drive-lab results and frames from a saved CI job log (see tools/print_drive_lab.py).

    python3 tools/extract_drive_lab.py job.log out-dir
"""
import base64
import re
import sys
from pathlib import Path

log, out = Path(sys.argv[1]), Path(sys.argv[2])
stamp = re.compile(r"^\d{4}-\d\d-\d\dT[\d:.]+Z ")
name, mode, chunks = None, None, []
for raw in log.read_text(errors="replace").splitlines():
    line = stamp.sub("", raw).rstrip()
    if line.startswith("DRIVE-LAB-FRAME-BEGIN "):
        name, mode, chunks = line.split(" ", 1)[1], "frame", []
    elif line.startswith("DRIVE-LAB-RESULTS-BEGIN "):
        name, mode, chunks = line.split(" ", 1)[1] + "/results.json", "json", []
    elif line in ("DRIVE-LAB-FRAME-END", "DRIVE-LAB-RESULTS-END") and name:
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if mode == "frame":
            target.write_bytes(base64.b64decode("".join(chunks)))
        else:
            target.write_text("\n".join(chunks))
        name, mode = None, None
    elif mode:
        chunks.append(line)
print("extracted to", out)
