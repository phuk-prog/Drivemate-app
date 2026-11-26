#!/usr/bin/env python3
"""Verify exact immutable map release assets against committed app pins."""
import hashlib,json,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAP="phuk-prog/Drivemate-map"
def digest(path):
    with path.open("rb") as stream:return hashlib.file_digest(stream,"sha256").hexdigest()
def main():
    lock=json.loads((ROOT/"release/map-lock.json").read_text())
    with tempfile.TemporaryDirectory() as d:
        root=Path(d)
        for pin,asset,sha in ((lock["world"],lock["world"]["asset"],lock["world"]["sha256"]),(lock["ukRouting"],"uk-routing-manifest.json",lock["ukRouting"]["manifestSha256"])):
            if "latest" in pin["tag"].lower():raise SystemExit("Mutable map tag prohibited")
            out=root/asset
            subprocess.run(["gh","release","download",pin["tag"],"--repo",MAP,"--pattern",asset,"--output",str(out)],check=True)
            if digest(out)!=sha:raise SystemExit("Map asset checksum mismatch: "+asset)
    print("Exact remote map assets verified; UK routing remains preview-only.")
if __name__=="__main__":main()
