#!/usr/bin/env python3
"""Check version source and durable published releases; fail closed."""
import argparse,json,re,subprocess,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def source():
    info=json.loads((ROOT/"release/version.json").read_text())
    gradle=(ROOT/"app/build.gradle").read_text()
    for field,pattern in [("versionCode",r"(?m)^\s*versionCode\s+(\d+)"),("versionName",r"(?m)^\s*versionName\s+'([^']+)'"),("applicationId",r"(?m)^\s*applicationId\s+'([^']+)'")]:
        m=re.search(pattern,gradle)
        if not m or m[1]!=str(info[field]):raise SystemExit("Version registry and Gradle mismatch: "+field)
    if not re.fullmatch(r"\d+\.\d+\.\d+",info["versionName"]) or not isinstance(info["versionCode"],int) or info["versionCode"]<=info["lastLegacyDistributedCode"]:
        raise SystemExit("Version must exceed legacy distributed floor")
    return info
def check_previous(info,releases):
    floor=info["lastLegacyDistributedCode"];seen=set()
    for release in releases:
        tag=release["tag_name"]
        if not re.fullmatch(r"v\d+\.\d+\.\d+",tag):continue
        manifest=json.loads(subprocess.check_output(["gh","release","download",tag,"--repo",os.environ["GH_REPO"],"--pattern","release-manifest.json","--output","-"],text=True))
        code=manifest["versionCode"]
        if not isinstance(code,int) or manifest["versionName"]!=tag[1:] or code in seen:raise SystemExit("Invalid or duplicate published version code")
        seen.add(code);floor=max(floor,code)
    if info["versionCode"]<=floor:raise SystemExit("Version code must strictly exceed published floor "+str(floor))
    return floor
def main():
    p=argparse.ArgumentParser();p.add_argument("--published",action="store_true");a=p.parse_args()
    info=source()
    if a.published:
        pages=json.loads(subprocess.check_output(["gh","api","--paginate","--slurp","repos/"+os.environ["GH_REPO"]+"/releases?per_page=100"],text=True))
        check_previous(info,[item for page in pages for item in page if not item["draft"]])
    print("Validated",info["versionName"],info["versionCode"])
if __name__=="__main__":main()
