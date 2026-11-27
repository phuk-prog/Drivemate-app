package com.drivemate.app;

import java.util.*;
import java.io.IOException;
import org.json.JSONObject;

/** Native source-way transitions. No invented exits, lanes, signs, limits or traffic. */
final class NativeManeuvers {
    private NativeManeuvers(){}
    private static double[] offsets(List<GeoMath.Point> p){
        double[] d=new double[p.size()];for(int i=1;i<d.length;i++)d[i]=d[i-1]+GeoMath.distance(p.get(i-1),p.get(i));return d;
    }
    private static void validate(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags)throws IOException{
        if(p.size()<2||ways.size()!=p.size()-1)throw new IOException("Native route identity mismatch");
        for(int i=0;i<p.size();i++){
            GeoMath.Point q=p.get(i);
            if(!Double.isFinite(q.lat)||!Double.isFinite(q.lon)||Math.abs(q.lat)>90||Math.abs(q.lon)>180
                    ||i>0&&(GeoMath.distance(p.get(i-1),q)<.01||!tags.containsKey(ways.get(i-1))))
                throw new IOException("Native route geometry or tags missing");
        }
    }
    static boolean roundabout(JSONObject t){return "roundabout".equals(t.optString("junction"))||"circular".equals(t.optString("junction"));}
    static String name(JSONObject t){String s=t.optString("name").trim();return s.length()<=120?s:"";}
    static String ref(JSONObject t){String s=t.optString("ref").trim();return s.matches("[A-Za-z0-9 /;-]{1,24}")?s:"";}
    private static String road(JSONObject t){String n=name(t);return n.isEmpty()?ref(t):n;}
    static double angle(GeoMath.Point before,GeoMath.Point at,GeoMath.Point after){
        return (GeoMath.bearingDegrees(at,after)-GeoMath.bearingDegrees(before,at)+540)%360-180;
    }
    /** Distance either side of a junction used to measure the turn, so bends in a long edge do not skew it. */
    static final double TURN_WINDOW_METRES=25;
    static List<TomTomApi.Instruction> steps(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags,int[] choices)throws IOException{
        return steps(p,ways,tags,choices,null);
    }
    /** Turn measured along the route either side of node i, falling back to the neighbouring nodes. */
    static double windowTurn(List<GeoMath.Point> p,double[] d,int i,double chordTurn){
        double total=d[d.length-1];
        GeoMath.Point at=p.get(i);
        GeoMath.Point before=GeoMath.pointAtDistance(p,Math.max(0,d[i]-TURN_WINDOW_METRES));
        GeoMath.Point after=GeoMath.pointAtDistance(p,Math.min(total,d[i]+TURN_WINDOW_METRES));
        if(GeoMath.distance(before,at)<3||GeoMath.distance(at,after)<3)return chordTurn;
        return angle(before,at,after);
    }
    /**
     * @param details per-node junction details from the engine (exit counts, forks), or null for plain wording.
     */
    static List<TomTomApi.Instruction> steps(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags,int[] choices,JunctionDetail[] details)throws IOException{
        validate(p,ways,tags);if(choices==null||choices.length!=p.size())throw new IOException("Missing junction choices");
        double[] d=offsets(p);List<TomTomApi.Instruction> result=new ArrayList<>();
        for(int i=1;i<p.size()-1;i++){
            JSONObject in=tags.get(ways.get(i-1)),out=tags.get(ways.get(i));
            boolean entering=!roundabout(in)&&roundabout(out),leaving=roundabout(in)&&!roundabout(out);
            String target=road(out);double turn=angle(p.get(i-1),p.get(i),p.get(i+1));
            String code="",message="",junction="REGULAR";int exit=0;double shown=turn;
            JSONObject refWay=out;
            if(entering){
                code="ROUNDABOUT";message="Follow the highlighted route at the roundabout";junction="ROUNDABOUT";target="";refWay=null;
                RoundaboutExits.Result r=RoundaboutExits.count(ways,tags,details,i);
                // The exit number must agree with the overall turn (a 1st exit is never a right turn).
                double overall=r.exit>0?overallTurn(p,d,i,r.exitWayIndex):Double.NaN;
                if(r.exit>0&&!RoundaboutExits.plausible(r.exit,overall))r=new RoundaboutExits.Result(0,-1);
                // A roundabout split by an untagged stretch would be counted twice: no number then.
                if(r.exit>0&&rejoinsRoundabout(ways,tags,d,r.exitWayIndex))r=new RoundaboutExits.Result(0,-1);
                if(r.exit>0){exit=r.exit;refWay=tags.get(ways.get(r.exitWayIndex));target=road(refWay);
                    boolean immediate=d[r.exitWayIndex]-d[i]<=IMMEDIATE_EXIT_METRES;
                    message=immediate&&exit==1?"At the roundabout, keep left and take the 1st exit straight away"
                            :"At the roundabout, take the "+GuidanceLogic.ordinal(exit)+" exit";}
            }
            else if(leaving){code="ROUNDABOUT_EXIT";message="Exit the roundabout";junction="ROUNDABOUT";}
            else if(roundabout(in)&&roundabout(out))continue;
            else if(ways.get(i-1).equals(ways.get(i)))continue;
            else{
                double measured=windowTurn(p,d,i,turn);shown=measured;
                boolean sameRoadReversed=!target.isEmpty()&&target.equals(road(in));
                TurnBands.Band band=(Math.abs(measured)>=TurnBands.BEAR_FROM&&(choices[i]>1||!road(in).equals(target)||Math.abs(measured)>=TurnBands.REVERSE_FROM))
                        ?TurnBands.classify(measured,sameRoadReversed,choices[i]>1):null;
                if(band!=null){code=band.code;message=band.message;}
                else if(Math.abs(measured)>=TurnBands.REVERSE_FROM){code="CONTINUE";message="Follow the highlighted route; check road signs";}
                else if(Math.abs(measured)<TurnBands.BEAR_FROM&&details!=null&&i<details.length&&details[i]!=null&&details[i].forkLeft!=details[i].forkRight){
                    TurnBands.Band keep=TurnBands.keep(details[i].forkRight);code=keep.code;message=keep.message;
                }
                else if(!target.isEmpty()&&!road(in).equals(target)){code="CONTINUE";message="Continue";}
                else continue;
            }
            if(!target.isEmpty())message+=" onto "+target;
            // The road number belongs to the road being joined, never to the roundabout itself.
            result.add(new TomTomApi.Instruction(d[i],message,code,junction,exit,shown,target,"","",refWay==null?"":ref(refWay),"LEFT"));
        }
        result.add(new TomTomApi.Instruction(d[d.length-1],"Arrive at your destination","DESTINATION","REGULAR",0,0,"",""));
        return Collections.unmodifiableList(result);
    }
    /** An exit this close after joining is effectively an immediate left. */
    static final double IMMEDIATE_EXIT_METRES=30;
    /** Overall turn from the approach before the roundabout to the road after the exit (positive right). */
    static double overallTurn(List<GeoMath.Point> p,double[] d,int enter,int exitNode){
        if(exitNode<=enter||exitNode>=p.size())return Double.NaN;
        double total=d[d.length-1];
        GeoMath.Point before=GeoMath.pointAtDistance(p,Math.max(0,d[enter]-20)),at=p.get(enter);
        GeoMath.Point leave=p.get(exitNode),after=GeoMath.pointAtDistance(p,Math.min(total,d[exitNode]+20));
        if(GeoMath.distance(before,at)<3||GeoMath.distance(leave,after)<3)return Double.NaN;
        return (GeoMath.bearingDegrees(leave,after)-GeoMath.bearingDegrees(before,at)+540)%360-180;
    }
    /** True when the route is back on a roundabout within 60 m of leaving it (a fragmented roundabout). */
    static boolean rejoinsRoundabout(List<Long> ways,Map<Long,JSONObject> tags,double[] d,int exitNode){
        for(int j=exitNode;j<ways.size()&&d[j]-d[exitNode]<=60;j++)if(roundabout(tags.get(ways.get(j))))return true;
        return false;
    }
    /** Lane sections from way lane tags, only where the tags are present and consistent. */
    static List<RouteIntelligence.LaneSection> lanes(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags,List<TomTomApi.Instruction> steps){
        return lanes(p,ways,tags,steps,null);
    }
    /**
     * Whether the route runs along each way's drawing direction, from the scene's way geometry
     * (index-aligned with segments; null entries = unknown).
     */
    static Boolean[] directions(List<GeoMath.Point> p,List<Long> ways,NativeMapScene scene){
        Boolean[] out=new Boolean[ways.size()];if(scene==null)return out;
        Map<Long,NativeMapScene.Shape> byId=new HashMap<>();
        for(NativeMapScene.Shape s:scene.shapes)if(!s.area)byId.put(s.id,s);
        for(int i=0;i<ways.size()&&i+1<p.size();i++){
            NativeMapScene.Shape s=byId.get(ways.get(i));if(s==null)continue;
            int a=vertex(s,p.get(i)),b=vertex(s,p.get(i+1));
            if(a>=0&&b>=0&&a!=b)out[i]=b>a;
        }
        return out;
    }
    private static int vertex(NativeMapScene.Shape s,GeoMath.Point q){
        double x=NativeMapProjection.x(q.lon),y=NativeMapProjection.y(q.lat);int best=-1;double bestD=1e-12;
        for(int k=0;k+1<s.xy.length;k+=2){double dx=s.xy[k]-x,dy=s.xy[k+1]-y,dd=dx*dx+dy*dy;if(dd<bestD){bestD=dd;best=k/2;}}
        return best;
    }
    static List<RouteIntelligence.LaneSection> lanes(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags,List<TomTomApi.Instruction> steps,Boolean[] forward){
        List<RouteIntelligence.LaneSection> out=new ArrayList<>();
        double[] d=offsets(p);
        for(TomTomApi.Instruction step:steps){
            if("DESTINATION".equals(step.maneuver))continue;
            // The way the route is on just before the junction.
            int node=-1;for(int i=1;i<d.length-1;i++)if(Math.abs(d[i]-step.routeOffsetMetres)<.01){node=i;break;}
            if(node<1)continue;
            JSONObject wayTags=tags.get(ways.get(node-1));
            double start=Math.max(0,step.routeOffsetMetres-LANE_SECTION_METRES);
            // Without direction data (legacy callers) the way's own direction is assumed, as before.
            Boolean dir=forward==null?Boolean.TRUE:node-1<forward.length?forward[node-1]:null;
            RouteIntelligence.LaneSection section=LaneTags.section(wayTags,dir,step.maneuver,step.turnAngleDegrees,start,step.routeOffsetMetres);
            if(section!=null)out.add(section);
        }
        return Collections.unmodifiableList(out);
    }
    static final double LANE_SECTION_METRES=300;
    static List<RouteIntelligence.SpeedSection> limits(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags)throws IOException{
        validate(p,ways,tags);double[] d=offsets(p);List<RouteIntelligence.SpeedSection> out=new ArrayList<>();
        for(int i=0;i<ways.size();i++){
            int kmh=MappedSpeedLimits.explicitLimit(tags.get(ways.get(i)));
            if(kmh<=0)continue;
            if(!out.isEmpty()){RouteIntelligence.SpeedSection last=out.get(out.size()-1);
                if(last.kmh==kmh&&Math.abs(last.end-d[i])<.01){out.set(out.size()-1,new RouteIntelligence.SpeedSection(last.start,d[i+1],kmh));continue;}}
            out.add(new RouteIntelligence.SpeedSection(d[i],d[i+1],kmh));
        }
        return Collections.unmodifiableList(out);
    }
}
