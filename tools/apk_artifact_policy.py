#!/usr/bin/env python3
"""Determine cleanup using server-created timestamps."""
import argparse, datetime as dt, json, re
UTC=dt.timezone.utc
TTL=3600
OFFICIAL=re.compile(r"DriveMate-\d+\.\d+\.\d+-signed-apk-1h")
LEGACY=re.compile(r"^drivemate.*(?:password-protected|encrypted)",re.I)
BINARY=re.compile(r"(?:^|[-_.\s])(?:apk|apks|xapk|apkm|aab)(?:$|[-_.\s])",re.I)
BUILD=re.compile(r"^drivemate.*[-_.](?:debug|unsigned)(?:$|[-_.])",re.I)
def decision(a,now):
    if a.get("expired"):return None
    name=a.get("name","")
    if "apksigner" in name.lower():return None
    if OFFICIAL.fullmatch(name) or LEGACY.match(name):
        if OFFICIAL.fullmatch(name) and (a.get("workflow_run") or {}).get("head_branch")!="main":return "untrusted signed artifact branch"
        try:
            created=dt.datetime.fromisoformat(a["created_at"].replace("Z","+00:00"))
            age=(now-created).total_seconds()
        except (KeyError,TypeError,ValueError):return "invalid artifact timestamp"
        if age<0:return "invalid future timestamp"
        return "one-hour APK expiry" if age>=TTL else None
    if BINARY.search(name) or BUILD.search(name):return "unapproved Android binary"
    return None
def main():
    p=argparse.ArgumentParser();p.add_argument("pages");a=p.parse_args()
    with open(a.pages) as stream:pages=json.load(stream)
    seen=set();now=dt.datetime.now(UTC)
    for page in pages:
        for item in page["artifacts"]:
            reason=decision(item,now);ident=item["id"]
            if reason and ident not in seen:
                seen.add(ident); print(f"{ident}\t{reason}\t{item['name'].replace(chr(10),' ').replace(chr(9),' ')}")
if __name__=="__main__":main()
