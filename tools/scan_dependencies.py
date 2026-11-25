#!/usr/bin/env python3
"""Fail closed on known Maven vulnerabilities in the resolved app lockfile.
Uses public Maven coordinates only; no repository secrets, local paths or source code are sent.
"""
import json,sys,urllib.request
from pathlib import Path

def coordinates(path):
    items=set()
    for line in Path(path).read_text().splitlines():
        if not line or line.startswith("#") or line.startswith("empty="):continue
        coordinate,sep,configs=line.partition("=")
        parts=coordinate.split(":")
        if not sep or len(parts)!=3 or not all(parts):raise ValueError("Invalid Gradle lock entry")
        items.add((parts[0]+":"+parts[1],parts[2]))
    if not items:raise ValueError("No resolved dependencies")
    return sorted(items)

def scan(items):
    findings=[]
    for start in range(0,len(items),100):
        group=items[start:start+100]
        queries=[{"package":{"ecosystem":"Maven","name":name},"version":version} for name,version in group]
        req=urllib.request.Request("https://api.osv.dev/v1/querybatch",data=json.dumps({"queries":queries}).encode(),headers={"Content-Type":"application/json"},method="POST")
        with urllib.request.urlopen(req,timeout=60) as response:
            raw=json.load(response)
        results=raw["results"]
        if len(results)!=len(group):raise ValueError("Incomplete OSV response")
        for (name,version),result in zip(group,results):
            if result.get("next_page_token"):raise ValueError("Unexpected paginated OSV result; scan incomplete")
            for advisory in result.get("vulns",[]):
                findings.append({"name":name,"version":version,"id":advisory["id"]})
    return findings

def main():
    items=coordinates(sys.argv[1])
    findings=scan(items)
    Path("dependency-security.json").write_text(json.dumps({"checked":len(items),"findings":findings},indent=2)+"\n")
    print("OSV checked",len(items),"resolved Maven dependencies;",len(findings),"findings")
    for v in findings:print(v["name"],v["version"],v["id"])
    return 1 if findings else 0
if __name__=="__main__":sys.exit(main())
