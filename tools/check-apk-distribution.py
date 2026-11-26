#!/usr/bin/env python3
"""Prevent direct Android binaries or broad build-output uploads in CI."""
from pathlib import Path
import re
import sys

errors = []
for workflow in Path(".github/workflows").glob("*.yml"):
    source = workflow.read_text()
    blocks = re.split(r"(?m)^      - ", source)
    for step in blocks:
        if "actions/upload-artifact@" not in step:
            continue
        if re.search(r"(?im)^\s*path:\s*[^\n]*\.(?:apk|aab|apks|xapk|apkm)\b", step):
            errors.append(f"{workflow}: raw Android binary upload")
        if re.search(r"(?im)^\s*(?:path:\s*)?app/build(?:/|\s|$)", step):
            errors.append(f"{workflow}: broad app build upload")
        if any(line.strip().endswith(".zip") and not line.strip().startswith("!") for line in step.splitlines()):
            if not ("password-protected" in step and "release-output/DriveMate-" in step
                    and "tools/encrypt-apk.sh" in source):
                errors.append(f"{workflow}: unapproved ZIP upload")
    for line in source.splitlines():
        if "gh release create" in line or "gh release upload" in line:
            if re.search(r"\.(?:apk|aab|apks|xapk|apkm)\b", line, re.I):
                errors.append(f"{workflow}: raw Android binary publication")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("APK distribution policy passed: no direct Android binary uploads.")
