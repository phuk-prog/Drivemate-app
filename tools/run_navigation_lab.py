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
def nodes(root):
    return list(root.iter("node")) if root is not None else []
def labels(root):
    return " ".join((n.get("text","")+" "+n.get("content-desc","")).strip() for n in nodes(root)).casefold()
def tap_node(n):
    nums=[int(x) for x in re.findall(r"\d+",n.get("bounds",""))]
    if len(nums)!=4 or nums[2]<=nums[0] or nums[3]<=nums[1]:return False
    a,b,c,d=nums;shell("input","tap",(a+c)//2,(b+d)//2);time.sleep(2);return True
def click(root,words=(),ids=()):
    matches=[]
    for n in nodes(root):
        if n.get("enabled")=="false":continue
        if n.get("resource-id","").split("/")[-1] in ids or n.get("text","").strip().casefold() in words or n.get("content-desc","").strip().casefold() in words:
            matches.append(n)
    # Duplicated nested labels in OsmAnd share the same action: pick clickable parent.
    clickable=[n for n in matches if n.get("clickable")=="true"]
    if len(clickable)==1:return tap_node(clickable[0])
    if len(matches)==1:return tap_node(matches[0])
    if matches and len({n.get("resource-id") for n in matches})==1:return tap_node(matches[0])
    return False
def install(pkg,folder):
    result=adb("install","-g",OUT/(pkg+".apk"),timeout=180,check=False).decode(errors="replace")
    (folder/"install.txt").write_text(result)
    if "Success" not in result:raise RuntimeError("APK installation did not succeed")
def launch(pkg):
    shell("monkey","-p",pkg,"-c","android.intent.category.LAUNCHER","1",check=False);time.sleep(3)
def provision(pkg,folder):
    # Online only BEFORE *any* simulated fix, with Android location disabled.
    launch(pkg);snap(folder,"setup-launch")
    root=ui(folder,"setup-first")
    if pkg=="org.navitproject.navit":
        if "welcome to navit" in labels(root):click(root,{"ok"}, {"button1"})
        path="/sdcard/Android/data/"+pkg+"/files"
        if (OUT/"navitmap.bin").exists():
            for sub in ("","/maps"):
                shell("mkdir","-p",path+sub,check=False)
                adb("push",OUT/"navitmap.bin",path+sub+"/navitmap.bin",timeout=120,check=False)
        shell("am","force-stop",pkg);launch(pkg)
    elif pkg=="no.navi.app":
        if "speed camera warnings" in labels(root):click(root,{"not now"})
        root=ui(folder,"setup-navi")
        if click(root,{"tools"}):
            root=ui(folder,"setup-navi-tools")
            click(root,{"download region","download a region","offline maps"})
            root=ui(folder,"setup-navi-region")
            click(root,{"united kingdom","england","south west england","somerset"})
        # Do not automatically submit an unknown region or opt in to telemetry.
    elif pkg=="net.osmand.plus":
        for obf in OUT.glob("*.obf"):
            path="/sdcard/Android/data/"+pkg+"/files"
            shell("mkdir","-p",path,check=False)
            adb("push",obf,path+"/"+obf.name,timeout=180,check=False)
        shell("am","force-stop",pkg);launch(pkg)
    elif pkg in ("app.organicmaps","app.comaps.fdroid"):
        if "download the world overview map" in labels(root):
            # Download the regional checkbox only when explicitly Bristol / South West.
            for n in nodes(root):
                if n.get("resource-id","").endswith("/chb_download_country") and ("bristol" in n.get("text","").casefold() or "south west england" in n.get("text","").casefold()) and n.get("checked")=="false":tap_node(n)
            click(root,{"download"},{"btn_download_resources"})
            deadline=time.monotonic()+240
            while time.monotonic()<deadline:
                time.sleep(5);root=ui(folder,"setup-map-progress")
                if "download the world overview map" not in labels(root):break
                if "error" in labels(root) or "failed" in labels(root):break
        root=ui(folder,"setup-after-overview")
        # Use official map-download UI; select only exact local-region labels.
        if click(root,{"menu","more","download maps"},{"menu","btn_menu"}):
            root=ui(folder,"setup-menu")
            click(root,{"download maps","downloaded maps","maps"})
        root=ui(folder,"setup-region-list")
        for region in ({"united kingdom"},{"england"},{"south west england"},{"south west england — bristol","south west england - bristol","bristol"}):
            if click(root,region):root=ui(folder,"setup-region-list")
        if "bristol" in labels(root) and click(root,{"download"},{"download_button","btn_download"}):
            time.sleep(45)
    snap(folder,"setup-final");ui(folder,"setup-final")
    shell("am","force-stop",pkg)
def route_state(root,pkg):
    # Strong positive indicators: explicit end-navigation UI plus routing dashboard.
    text=labels(root);ids={n.get("resource-id","").split("/")[-1] for n in nodes(root)}
    blockers=("download the world overview map","welcome to navit","speed camera warnings","download maps to","route not found","unable to calculate","no route found","no maps")
    for b in blockers:
        if b in text:return False,b
    active_words=("stop navigation","end navigation","cancel navigation","finish navigation")
    active_id=bool(ids.intersection({"navigation_stop","stop_navigation","nav_stop","routing_stop","btn_stop_navigation","navigation_cancel","stop_routing"}))
    metrics=any(x in text for x in ("arrival","remaining","next turn","distance","eta")) or bool(ids.intersection({"next_turn","next_turn_distance","navigation_dashboard","routing_info","navigation_info","routing_turn_distance","time_to_destination","distance_to_destination"}))
    if (active_id or any(x in text for x in active_words)) and metrics:return True,"explicit navigation stop control and trip/turn evidence"
    return False,"no positive active-navigation evidence"
def fix(p):
    # Android emulator accepts velocity in knots. Heading is derived from common track, not injected.
    r=adb("emu","geo","fix",f'{p["lon"]:.7f}',f'{p["lat"]:.7f}',0,10,f'{p["mps"]*1.943844:.3f}')
    if b"OK" not in r:raise RuntimeError("Emulator rejected GPS fix: "+r.decode(errors="replace"))
def record(folder,package,uri):
    start=trace["points"][0];fix(start)
    launch(package);snap(folder,"00-launch")
    root=ui(folder,"00-launch")
    if package=="org.navitproject.navit" and "welcome to navit" in labels(root):click(root,{"ok"},{"button1"})
    if package=="no.navi.app" and "speed camera warnings" in labels(root):click(root,{"not now"})
    intent=shell("am","start","-W","-a","android.intent.action.VIEW","-d",uri,"-p",package,check=False)
    (folder/"intent.txt").write_text(intent)
    time.sleep(6)
    snap(folder,"01-route-request");root=ui(folder,"01-route-request")
    # OsmAnd's explicit navigation link may land on map; geo pin fallback.
    if package=="net.osmand.plus" and not any(x in labels(root) for x in ("start navigation","directions","start","finish","stop navigation")):
        fallback=shell("am","start","-W","-a","android.intent.action.VIEW","-d","geo:"+",".join(map(str,trace["finish"]))+"?z=17","-p",package,check=False)
        (folder/"intent-fallback.txt").write_text(fallback);time.sleep(3);root=ui(folder,"01-fallback")
    if "unable to resolve intent" in intent.casefold() and package=="no.navi.app":
        # Search by coordinates using the documented app UI, not unsupported geo intent.
        if click(root,{"search","destination","where to?"}):
            root=ui(folder,"navi-search")
            fields=[n for n in nodes(root) if n.get("class")=="android.widget.EditText"]
            if len(fields)==1:
                tap_node(fields[0]);shell("input","text",",".join(map(str,trace["finish"])));shell("input","keyevent","66");time.sleep(3)
    confirmed=False;reason="no active navigation evidence"
    for i in range(7):
        root=ui(folder,f"prepare-{i}")
        confirmed,reason=route_state(root,package)
        if confirmed:break
        if not click(root,{"directions","route to","start","start navigation","navigate","go","drive"},{"btn_start","start_navigation","route_start","routing_start_button","btn_route_to","map_route_info_button"}):break
    snap(folder,"02-before-replay");root=ui(folder,"02-before-replay")
    confirmed,reason=route_state(root,package)
    foreground=shell("dumpsys","activity","activities",check=False)
    (folder/"foreground-before.txt").write_text(foreground)
    if not confirmed:
        return {"status":"blocked before GPS replay","fixes":0,"video":False,"audio_stream":False,"navigation_confirmed":False,"reason":reason,"route_intent":intent.strip()[:1000]}
    video=folder/"replay.webm";limit=min(600,len(trace["points"])+12)
    rec=adb("emu","screenrecord","start","--time-limit",limit,video,check=False).decode(errors="replace")
    (folder/"recording-start.txt").write_text(rec)
    began=time.monotonic();timeline=[]
    for p in trace["points"]:
        wait=began+p["t"]-time.monotonic()
        if wait>0:time.sleep(wait)
        fix(p);timeline.append({"t":p["t"],"elapsed":round(time.monotonic()-began,3),"event":p["event"]})
        if p["t"] in trace.get("capture_times",[]) or p["t"]==len(trace["points"])-1:
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
    peak=None
    if "audio" in streams:
        audio=subprocess.run(["ffmpeg","-hide_banner","-i",str(video),"-vn","-af","volumedetect","-f","null","-"],capture_output=True,text=True)
        (folder/"audio-levels.txt").write_text(audio.stderr)
        match=re.search(r"max_volume:\s*(-?[0-9.]+) dB",audio.stderr)
        if match:peak=float(match.group(1))
    return {"status":"navigation-active replay; review directions","fixes":len(timeline),"video":"video" in streams,"audio_stream":"audio" in streams,"audio_peak_dbfs":peak,"audible_signal":peak is not None and peak>-55,"route_intent":intent.strip()[:1000],"navigation_confirmed":True,"reason":"Positive UI gate; spoken content and junction accuracy need review"}
def main():
    results={};ready=[]
    # Preflight map downloads before any mock location and with device location off.
    shell("cmd","location","set-location-enabled","false")
    location=shell("cmd","location","is-location-enabled").strip().lower()
    if location!="false":raise RuntimeError("Cannot prove location disabled before map setup")
    shell("cmd","connectivity","airplane-mode","disable");shell("svc","wifi","enable");time.sleep(5)
    # No emu geo fix until after all apps are provisioned and all connectivity disabled.
    for pkg,meta in packages.items():
        folder=E/pkg;folder.mkdir(exist_ok=True);results[pkg]={**meta}
        if meta["status"]!="downloaded":continue
        try:
            install(pkg,folder);provision(pkg,folder);ready.append(pkg)
            results[pkg]["status"]="installed and setup attempted"
        except Exception as e:results[pkg].update(status="setup blocked",reason=str(e))
        (E/"reference-results.json").write_text(json.dumps(results,indent=2))
    for pkg in packages:shell("am","force-stop",pkg,check=False)
    shell("cmd","connectivity","airplane-mode","enable");shell("svc","wifi","disable");shell("svc","data","disable")
    time.sleep(5);connected=shell("dumpsys","connectivity");(E/"connectivity.txt").write_text(connected)
    if not re.search(r"Active default network:\s*(none|null)",connected,re.I):raise RuntimeError("Cannot prove emulator offline")
    shell("cmd","location","set-location-enabled","true")
    start=",".join(map(str,trace["start"]));end=",".join(map(str,trace["finish"]))
    route=f"route?sll={start}&saddr=LabStart&dll={end}&daddr=LabFinish&type=vehicle"
    uris={"net.osmand.plus":f"https://osmand.net/map/navigate?start={start}&end={end}&finish={end}&profile=car","app.organicmaps":"om://"+route,"app.comaps.fdroid":"cm://"+route,"org.navitproject.navit":"geo:"+end,"no.navi.app":"geo:"+end}
    for pkg in ready:
        folder=E/pkg
        try:
            results[pkg].update(record(folder,pkg,uris[pkg]))
        except Exception as e:results[pkg].update(status="blocked",reason=str(e))
        finally:
            adb("emu","screenrecord","stop",check=False);shell("am","force-stop",pkg,check=False)
        print("LAB_RESULT",pkg,json.dumps(results[pkg]),flush=True)
        (E/"reference-results.json").write_text(json.dumps(results,indent=2))
    (E/"reference-results.json").write_text(json.dumps(results,indent=2))
if __name__=="__main__":main()
