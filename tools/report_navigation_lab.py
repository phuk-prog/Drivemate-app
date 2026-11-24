#!/usr/bin/env python3
"""Evidence-grounded report. A successful script run is never a navigation pass."""
import json,html,collections
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"navigation-lab"
e=root/"evidence";e.mkdir(exist_ok=True)
def read(path,default):
    try:return json.loads(path.read_text())
    except (OSError,ValueError):return default
trace=read(root/"trace.json",{})
packages=read(root/"packages.json",{})
references=read(e/"reference-results.json",{})
names={"net.osmand.plus":"OsmAnd","app.organicmaps":"Organic Maps","app.comaps.fdroid":"CoMaps","org.navitproject.navit":"Navit","no.navi.app":"Navi"}
engine=next(e.glob("drivemate/**/drivemate-engine-results.json"),None)
own=read(engine,[]) if engine else []
fix_count=len(trace.get("points",[]))
rows=[{"application":"DriveMate","scope":"independent engine and renderer only","status":"engine replay completed" if own and len(own)==fix_count else "no complete engine evidence","fixes":len(own),"navigation_confirmed":False,"video":False,"audio_stream":False,"reason":"Production visual/audio guidance not exercised","frames":len(list(e.glob("drivemate/**/*.png"))),"states":dict(collections.Counter(x.get("status","unknown") for x in own))}]
for pkg,name in names.items():
    row={**packages.get(pkg,{}),**references.get(pkg,{})}
    rows.append({"application":name,"scope":"actual Android application","status":row.get("status","not run"),"fixes":row.get("fixes",0),"navigation_confirmed":row.get("navigation_confirmed") is True,"video":row.get("video",False),"audio_stream":row.get("audio_stream",False),"audio_peak_dbfs":row.get("audio_peak_dbfs"),"audible_signal":row.get("audible_signal",False),"reason":row.get("reason","Active navigation and spoken instructions require review" if row.get("fixes") else "No completed replay"),"version":row.get("version","unknown"),"frames":len(list((e/pkg).glob("*.png")))})
confirmed=sum(r["navigation_confirmed"] for r in rows)
report={"comparison_complete":False,"confirmed_navigation_apps":confirmed,"target_apps":len(rows),"trace":{"name":trace.get("name"),"metres":trace.get("metres"),"fixes":fix_count,"traffic":trace.get("traffic")},"results":rows,"junction_comparison":"Not yet scored: needs matched navigation-active evidence and actual spoken prompts","turn_candidates":trace.get("turn_candidates",[]),"warnings":["Simulated vehicle slowdown is not an injected traffic feed","Audio stream presence does not establish audible instructions","CoMaps and Organic Maps share ancestry","No app ranking until equivalent evidence exists"]}
(e/"comparison-report.json").write_text(json.dumps(report,indent=2))
lines=["# DriveMate Navigation Lab — comparison report","","**INCOMPLETE — no app ranking until actual guidance is verified.**","",f"Journey: {trace.get('name','unavailable')}; {trace.get('metres',0):.0f} m; {fix_count} scheduled fixes.","",f"Confirmed navigation sessions: {confirmed}/{len(rows)}.","","| Application | Evidence scope | Status | GPS fixes | Screenshots | Video | Audio stream | Blocker / caveat |","|---|---|---|---:|---:|---|---|---|"]
def safe(s):return str(s).replace("|","\\|").replace("\n"," ")
for r in rows:lines.append("| "+" | ".join(safe(x) for x in [r["application"],r["scope"],r["status"],r["fixes"],r["frames"],"yes" if r["video"] else "no","yes" if r["audio_stream"] else "no",r["reason"]])+" |")
lines+=["","## Junction-level comparison",f"{len(trace.get('turn_candidates',[]))} geometry turn candidates; captures before, during and after turns on confirmed replays. No validated spoken instruction scores yet.","## Audio levels",*[(r["application"]+": "+str(r.get("audio_peak_dbfs"))+" dBFS; audible signal "+str(r.get("audible_signal",False))) for r in rows],"","## DriveMate engine state counts",json.dumps(rows[0]["states"]),"","## Limitations"]+["- "+w for w in report["warnings"]]
(e/"comparison-report.md").write_text("\n".join(lines)+"\n")
print("\n".join(lines),flush=True)
