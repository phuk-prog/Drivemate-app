#!/usr/bin/env python3
"""Explicitly advance the version registry and matching Gradle version together."""
import argparse, json, re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def advance(info, gradle, name):
    if not re.fullmatch(r"\d+\.\d+\.\d+",name):
        raise ValueError("Use MAJOR.MINOR.PATCH")
    if tuple(map(int,name.split(".")))<=tuple(map(int,info["versionName"].split("."))):
        raise ValueError("Version name must advance")
    code=info["versionCode"]+1
    if code<=info["lastLegacyDistributedCode"] or code>2100000000:
        raise ValueError("Invalid next version code")
    for field, pattern in (("versionCode",r"(?m)^(\s*versionCode\s+)(\d+)"),("versionName",r"(?m)^(\s*versionName\s+')([^']+)(')")):
        matches=list(re.finditer(pattern,gradle))
        if len(matches)!=1 or matches[0].group(2)!=str(info[field]):
            raise ValueError("Registry and Gradle disagree: "+field)
        m=matches[0];replacement=str(code) if field=="versionCode" else name
        gradle=gradle[:m.start(2)]+replacement+gradle[m.end(2):]
    return dict(info,versionName=name,versionCode=code),gradle

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("version");p.add_argument("--apply",action="store_true");a=p.parse_args()
    registry=ROOT/"release/version.json";build=ROOT/"app/build.gradle"
    v,g=advance(json.loads(registry.read_text()),build.read_text(),a.version)
    print(json.dumps(v,indent=2))
    if a.apply:
        registry.write_text(json.dumps(v,indent=2)+"\n");build.write_text(g)
if __name__=="__main__":main()
