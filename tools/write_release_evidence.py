#!/usr/bin/env python3
"""Generate public non-secret provenance for an encrypted review artifact."""
import argparse, hashlib, json, os, subprocess
from pathlib import Path
def main():
    p=argparse.ArgumentParser();p.add_argument("--directory",required=True,type=Path);a=p.parse_args()
    version=json.loads(Path("release/version.json").read_text())
    pin=json.loads(Path("release/map-lock.json").read_text())
    files=list(a.directory.glob("*.zip"))
    if len(files)!=1:raise SystemExit("Expected exactly one encrypted ZIP")
    archive=files[0]
    with archive.open("rb") as stream:digest=hashlib.file_digest(stream,"sha256").hexdigest()
    version.update(commit=os.environ["GITHUB_SHA"],run_id=os.environ["GITHUB_RUN_ID"],encrypted_zip=archive.name,sha256=digest,map=pin,road_validated=False)
    (a.directory/"release-manifest.json").write_text(json.dumps(version,indent=2)+"\n")
    # The known signed 2.0.1 build is the initial reviewed changelog baseline.
    baseline="c6b6ee6ff7c841f88dcb88f4b995c9a1ea15cbc6"
    subprocess.run(["git","merge-base","--is-ancestor",baseline,"HEAD"],check=True)
    lines=subprocess.check_output(["git","log","--first-parent","--format=- %h %s",baseline+"..HEAD"],text=True)
    (a.directory/"CHANGELOG.md").write_text("# DriveMate "+version["versionName"]+" review candidate\n\nCommit summary since 2.0.1 (review before publication):\n\n"+lines+"\nAutomated evidence only; not physically road validated.\n")
if __name__=="__main__":main()
