#!/usr/bin/env python3
"""Replay a common trace on actual emulator apps. No assumption that an intent starts guidance."""
import json,subprocess,time,re,shlex,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/"navigation-lab";E=OUT/"evidence";E.mkdir(exist_ok=True)
trace=json.loads((OUT/"trace.json").read_text());packages=json.loads((OUT/"packages.json").read_text())
def adb(*args,timeout=30,check=True):
    p=subprocess.run(["adb",*map(str,args)],capture_output=True,timeout=timeout)
    if check and p.returncode:raise RuntimeError(p.stderr.decode(errors="replace")[-500:])
    return p.stdout
def shell(*args,check=True,timeout=30):return adb("shell",shlex.join(map(str,args)),check=check,timeout=timeout).decode(errors="replace")
def snap(folder,label):
    (folder/(label+".png")).write_bytes(adb("exec-out","screencap","-p"))
    return shell("dumpsys","activity","activities",check=False)
def ui(folder,label):
    shell("rm","-f","/sdcard/dm-lab-window.xml",check=False)
    shell("uiautomator","dump","/sdcard/dm-lab-window.xml",check=False,timeout=20)
    raw=adb("exec-out","cat","/sdcard/dm-lab-window.xml",check=False)
    (folder/(label+".xml")).write_bytes(raw)
    try:return ET.fromstring(raw)
    except Exception:return None
def click_exact(folder,label,words):
    root=ui(folder,label)
    if root is None:return False
    matches=[]
    for n in root.iter("node"):
        if n.get("text","").strip().casefold() not in words and n.get("content-desc","").strip().casefold() not in words:continue
        nums=[int(x) for x in re.findall(r"\d+",n.get("bounds",""))]
        if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:matches.append(nums)
    if len(matches)!=1:return False
    a,b,c,d=matches[0];shell("input","tap",(a+c)//2,(b+d)//2);time.sleep(3);return True
def fix(p):
    # Android emulator accepts velocity in knots. Heading is derived from common track, not injected.
    r=adb("emu","geo","fix",f'{p["lon"]:.7f}',f'{p["lat"]:.7f}',0,10,f'{p["mps"]*1.943844:.3f}')
    if b"OK" not in r:raise RuntimeError("Emulator rejected GPS fix: "+r.decode(errors="replace"))
def record(folder,package,uri):
    start=trace["points"][0];fix(start)
    shell("monkey","-p",package,"-c","android.intent.category.LAUNCHER","1",check=False);time.sleep(4)
    snap(folder,"00-launch")
    if package=="org.navitproject.navit" and (OUT/"navitmap.bin").exists():
        path="/sdcard/Android/data/"+package+"/files"
        shell("mkdir","-p",path,check=False);adb("push",OUT/"navitmap.bin",path+"/navitmap.bin",timeout=120,check=False)
        shell("am","force-stop",package)
    if package=="net.osmand.plus":
        for obf in OUT.glob("*.obf"):
            path="/sdcard/Android/data/"+package+"/files"
            shell("mkdir","-p",path,check=False);adb("push",obf,path+"/"+obf.name,timeout=180,check=False)
        shell("am","force-stop",package)
    intent=shell("am","start","-W","-a","android.intent.action.VIEW","-d",uri,"-p",package,check=False)
    (folder/"intent.txt").write_text(intent)
    time.sleep(8)
    snap(folder,"01-route-request");ui(folder,"01-route-request")
    # Only activate explicitly labelled navigation controls, never accept unknown terms,
    # sign in, report traffic, grant unrelated permissions or bypass onboarding.
    for i in range(4):
        if not click_exact(folder,f"prepare-{i}",{"directions","route to","start","start navigation","navigate"}):break
    snap(folder,"02-before-replay");ui(folder,"02-before-replay")
    # Capture foreground identity as well as the screenshot, not a guessed navigation state.
    (folder/"foreground-before.txt").write_text(shell("dumpsys","activity","activities",check=False))
    video=folder/"replay.webm";limit=min(600,len(trace["points"])+12)
    rec=adb("emu","screenrecord","start","--time-limit",limit,video,check=False).decode(errors="replace")
    (folder/"recording-start.txt").write_text(rec)
    began=time.monotonic();timeline=[]
    for p in trace["points"]:
        wait=began+p["t"]-time.monotonic()
        if wait>0:time.sleep(wait)
        fix(p);timeline.append({"t":p["t"],"elapsed":round(time.monotonic()-began,3),"event":p["event"]})
        if p["t"]%20==0 or p["t"]==len(trace["points"])-1:
            snap(folder,f'fix-{p["t"]:04d}')
    adb("emu","screenrecord","stop",check=False)
    snap(folder,"03-arrival");ui(folder,"03-arrival")
    (folder/"foreground-after.txt").write_text(shell("dumpsys","activity","activities",check=False))
    (folder/"timeline.json").write_text(json.dumps(timeline,indent=2))
    streams=[]
    if video.exists() and video.stat().st_size>0:
        probe=subprocess.run(["ffprobe","-v","error","-show_streams","-of","json",str(video)],capture_output=True,text=True)
        try:streams=[s["codec_type"] for s in json.loads(probe.stdout)["streams"]]
        except Exception:pass
    return {"status":"replayed; guidance unverified","fixes":len(timeline),"video": "video" in streams,"audio_stream":"audio" in streams,"route_intent":intent.strip()[:1000],"navigation_confirmed":False}
def main():
    # Isolate ALL reference app tests from public services, including traffic feeds.
    shell("cmd","connectivity","airplane-mode","enable");shell("svc","wifi","disable");shell("svc","data","disable")
    time.sleep(5);(E/"connectivity.txt").write_text(shell("dumpsys","connectivity"))
    # Verify active connectivity is absent before any simulated GPS fix.
    connected=shell("dumpsys","connectivity")
    if not re.search(r"Active default network:\s*(none|null)",connected,re.I):raise RuntimeError("Cannot prove emulator offline")
    start=",".join(map(str,trace["start"]));end=",".join(map(str,trace["finish"]))
    route=f"route?sll={start}&saddr=LabStart&dll={end}&daddr=LabFinish&type=vehicle"
    uris={"net.osmand.plus":f"https://osmand.net/map/?start={start}&finish={end}&profile=car","app.organicmaps":"om://"+route,"app.comaps.fdroid":"cm://"+route,"org.navitproject.navit":"geo:"+end,"no.navi.app":"geo:"+end}
    results={}
    for pkg,meta in packages.items():
        folder=E/pkg;folder.mkdir(exist_ok=True)
        results[pkg]={**meta}
        if meta["status"]!="downloaded":
            print("LAB_RESULT",pkg,json.dumps(results[pkg]),flush=True)
            continue
        try:
            install=adb("install","-g",OUT/(pkg+".apk"),timeout=180,check=False).decode(errors="replace")
            (folder/"install.txt").write_text(install)
            if "Success" not in install:raise RuntimeError("APK installation did not succeed")
            results[pkg].update(record(folder,pkg,uris[pkg]))
        except Exception as e:results[pkg].update(status="blocked",reason=str(e))
        finally:
            adb("emu","screenrecord","stop",check=False)
            shell("am","force-stop",pkg,check=False)
            adb("uninstall",pkg,timeout=60,check=False)
        print("LAB_RESULT",pkg,json.dumps(results[pkg]),flush=True)
        (E/"reference-results.json").write_text(json.dumps(results,indent=2))
    (E/"reference-results.json").write_text(json.dumps(results,indent=2))
if __name__=="__main__":main()
