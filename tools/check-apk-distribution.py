#!/usr/bin/env python3
"""Only the main-only signing job may upload a single verified release APK."""
from pathlib import Path
import re,sys
errors=[]
official_count=0
for workflow in Path(".github/workflows").glob("*.yml"):
    source=workflow.read_text()
    for step in re.split(r"(?m)^      - ",source):
        if "actions/upload-artifact@" not in step:continue
        paths=[]
        in_path=False
        for line in step.splitlines():
            if re.match(r"^          path:\s*",line):
                value=line.split(":",1)[1].strip()
                in_path=value=="|"
                if value and value!="|":paths.append(value)
            elif in_path and line.startswith("            "):paths.append(line.strip())
            elif in_path:in_path=False
        positive=[p for p in paths if not p.startswith("!")]
        binaries=[p for p in positive if re.search(r"\.(?:apk|aab|apks|xapk|apkm|zip)(?:$|\b)",p,re.I)]
        if binaries:
            exact="release-output/DriveMate-${{ env.VERSION }}-signed.apk"
            allowed=(workflow.name=="signed-release.yml" and positive==[exact] and
                "name: DriveMate-${{ env.VERSION }}-signed-apk-1h" in step and
                "retention-days: 1" in step and "if-no-files-found: error" in step and
                "github.ref == 'refs/heads/main'" in source and "environment: drivemate-release" in source and
                source.index("Verify expected DriveMate certificate")<source.index("Stage only certificate-verified signed APK")<source.index("Upload verified signed APK"))
            if allowed:official_count+=1
            else:errors.append(f"{workflow}: unapproved installable binary or ZIP upload")
        for p in positive:
            if p.startswith("app/build") or p.startswith("release-output") and "*" in p:
                errors.append(f"{workflow}: broad binary upload")
        if not positive:errors.append(f"{workflow}: missing explicit artifact path")
    if re.search(r"gh release (?:create|upload)",source):
        errors.append(f"{workflow}: release asset publication is forbidden by temporary-delivery policy")
    if workflow.name=="signed-release.yml" and "DRIVEMATE_ZIP_PASSWORD" in source:errors.append("Obsolete ZIP secret dependency")
if official_count!=1:errors.append("Expected exactly one controlled signed APK artifact upload")
if errors:sys.exit("\n".join(errors))
print("APK distribution policy passed: only main-only certificate-verified signed APK may be uploaded; one-day native retention plus expiry cleanup.")
