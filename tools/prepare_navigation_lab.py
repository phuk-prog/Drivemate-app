#!/usr/bin/env python3
"""Prepare a real OSM road replay and verified public F-Droid reference APKs.
No commercial accounts, APK mirrors, simulated live traffic submissions or secrets.
"""
import hashlib,heapq,json,math,re,shutil,subprocess,sys,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/"navigation-lab";OUT.mkdir(exist_ok=True)
APPS={"OsmAnd":"net.osmand.plus","Organic Maps":"app.organicmaps","CoMaps":"app.comaps.fdroid","Navit":"org.navitproject.navit","Navi":"no.navi.app"}
FDROID="43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
def fetch(url,path,maxbytes):
    h=hashlib.sha256();size=0
    try:
        req=urllib.request.Request(url,headers={"User-Agent":"DriveMate-QA/1.0"})
        with urllib.request.urlopen(req,timeout=60) as r,path.open("wb") as f:
            while True:
                b=r.read(1024*1024)
                if not b:break
                size+=len(b)
                if size>maxbytes:raise ValueError("Download size bound")
                h.update(b);f.write(b)
        return h.hexdigest(),size
    except Exception:
        path.unlink(missing_ok=True);raise
def make_trace():
    sys.path.insert(0,str(ROOT/"offline-pilot-work"))
    from verify_uk_graph import OfflineRoads,metres
    db=ROOT/"app/src/androidTest/assets/wells-routing.sqlite"
    g=OfflineRoads(db)
    start=(51.209,-2.647);end=(51.207,-2.641)
    a=g.nearest(start);b=g.nearest(end)
    if not a or not b:raise ValueError("Unsnappable journey")
    q=[(0,0,a[0],0,0,a[1])];best={0:0};parent={};coords={0:a[1]}
    while q:
        cost,eid,node,prev,way,pos=heapq.heappop(q)
        if cost>best[eid]+1e-9:continue
        if node==b[0]:
            ids=[eid]
            while ids[-1]:ids.append(parent[ids[-1]])
            line=[coords[i] for i in reversed(ids)];break
        rules=g.rules(node,way) if eid else ()
        for edge,to,newway,seconds,lat,lon,length in g.outgoing(node):
            if eid and not g.turn_allowed(prev,to,newway,rules):continue
            val=cost+seconds
            if val>=best.get(edge,math.inf):continue
            best[edge]=val;parent[edge]=eid;coords[edge]=(lat,lon)
            heapq.heappush(q,(val,edge,to,node,newway,(lat,lon)))
    else:raise ValueError("No connected replay route")
    g.close()
    cumulative=[0.0]
    for p,n in zip(line,line[1:]):cumulative.append(cumulative[-1]+metres(p,n))
    total=cumulative[-1]
    if not 200<total<5000:raise ValueError("Unexpected replay distance")
    def at(d):
        for i in range(1,len(line)):
            if cumulative[i]>=d and cumulative[i]>cumulative[i-1]:
                r=(d-cumulative[i-1])/(cumulative[i]-cumulative[i-1])
                return [line[i-1][j]+r*(line[i][j]-line[i-1][j]) for j in range(2)]
        return list(line[-1])
    points=[];d=0;stop=False;slow_until=0;hold=0
    for i in range(5):points.append({"t":i,"lat":line[0][0],"lon":line[0][1],"mps":0,"event":"stationary_start"})
    while d<total:
        if not stop and d>=total*.4:stop=True;hold=8;slow_until=d+80
        if hold:velocity=0;event="synthetic_stop";hold-=1
        else:velocity=2 if d<slow_until else 8;event="synthetic_slowdown" if velocity==2 else "driving"
        p=at(d);points.append({"t":len(points),"lat":p[0],"lon":p[1],"mps":velocity,"event":event})
        d=min(total,d+velocity)
    for i in range(8):points.append({"t":len(points),"lat":line[-1][0],"lon":line[-1][1],"mps":0,"event":"arrival"})
    trace={"name":"Wells local road replay","start":start,"finish":end,"snap_start":line[0],"snap_finish":line[-1],"metres":total,"source_sha256":"8cde16000a69096b62f7a75598fb892c2d8eea34b8f2237278a785ddf125ce26","traffic":"stationary and slow vehicle speed only; no injected traffic data","points":points}
    (OUT/"trace.json").write_text(json.dumps(trace,indent=2))
    (ROOT/"app/src/androidTest/assets/navigation-lab.json").write_text(json.dumps(trace))
    print("LAB_TRACE",len(points),"fixes",round(total),"metres",flush=True)
def download_apps():
    # F-Droid v1 deliberately uses legacy SHA-1. Modern v2 has a signed
    # entry.json committing to the exact full index size and SHA-256.
    jar=OUT/"fdroid-entry.jar"
    fetch("https://f-droid.org/repo/entry.jar",jar,2_000_000)
    verified=OUT/"entry-verified.json"
    verified.unlink(missing_ok=True)
    subprocess.run(["java",str(ROOT/"tools/VerifyFdroidIndex.java"),str(jar),FDROID,str(verified)],check=True,timeout=90)
    entry=json.loads(verified.read_text())
    meta=entry["index"]
    if meta["name"] not in ("/index-v2.json","index-v2.json"):raise ValueError("Unexpected signed index path")
    if not 0<meta["size"]<=250_000_000:raise ValueError("Index exceeds bound")
    import time
    age=time.time()-int(entry["timestamp"])/1000
    if age>30*86400 or age< -86400:raise ValueError("Stale or future repository entry")
    index_path=OUT/"index-v2.json"
    sha,size=fetch("https://f-droid.org/repo/index-v2.json",index_path,meta["size"])
    if sha!=meta["sha256"] or size!=meta["size"]:raise ValueError("Index hash/size mismatch")
    index=json.loads(index_path.read_text())
    print("LAB_INDEX_VERIFIED_V2",FDROID,sha,flush=True)
    result={}
    for name,pkg in APPS.items():
        try:
            versions=index["packages"][pkg]["versions"].values()
            compatible=[]
            for v in versions:
                m=v["manifest"];abi=m.get("nativecode",[])
                if int((m.get("usesSdk") or {}).get("minSdkVersion",1))>34:continue
                if abi and not any(a in abi for a in ("x86_64","x86")):continue
                if not v["file"]["name"].endswith(".apk"):continue
                compatible.append(v)
            if not compatible:raise ValueError("No compatible x86 Android build in signed index")
            v=max(compatible,key=lambda v:int(v["manifest"]["versionCode"]))
            m=v["manifest"];f=v["file"];filename=f["name"].lstrip("/")
            if not re.fullmatch(r"[A-Za-z0-9._-]+\\.apk",filename):raise ValueError("Unsafe APK filename")
            sha,size=fetch("https://f-droid.org/repo/"+filename,OUT/(pkg+".apk"),300_000_000)
            if sha!=f["sha256"] or size!=f["size"]:
                (OUT/(pkg+".apk")).unlink();raise ValueError("APK checksum or size mismatch")
            result[pkg]={"name":name,"version":m.get("versionName","unknown"),"versionCode":m["versionCode"],"sha256":sha,"bytes":size,"status":"downloaded"}
        except Exception as e:result[pkg]={"name":name,"status":"blocked","reason":str(e)}
        print("LAB_PACKAGE",pkg,result[pkg],flush=True)
    (OUT/"packages.json").write_text(json.dumps(result,indent=2))
if __name__=="__main__":
    make_trace()
    try:download_apps()
    except Exception as e:
        (OUT/"packages.json").write_text(json.dumps({p:{"name":n,"status":"blocked","reason":str(e)} for n,p in APPS.items()},indent=2))
        print("LAB_PACKAGE_INDEX_BLOCKED",str(e),flush=True)
