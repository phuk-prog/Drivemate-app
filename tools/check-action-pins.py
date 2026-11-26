#!/usr/bin/env python3
import re
from pathlib import Path
bad=[]
for path in Path(".github/workflows").glob("*.yml"):
    for line in path.read_text().splitlines():
        match=re.match(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)",line)
        if match and not match[1].startswith("./") and not re.fullmatch(r"[^@]+@[0-9a-f]{40}",match[1]):
            bad.append(str(path)+": "+match[1])
if bad:raise SystemExit("Mutable workflow action reference:\n"+"\n".join(bad))
print("All external action references pinned to full commit hashes.")
