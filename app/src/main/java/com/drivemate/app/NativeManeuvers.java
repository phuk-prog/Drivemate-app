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
    static List<TomTomApi.Instruction> steps(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags,int[] choices)throws IOException{
        validate(p,ways,tags);if(choices==null||choices.length!=p.size())throw new IOException("Missing junction choices");
        double[] d=offsets(p);List<TomTomApi.Instruction> result=new ArrayList<>();
        for(int i=1;i<p.size()-1;i++){
            JSONObject in=tags.get(ways.get(i-1)),out=tags.get(ways.get(i));
            boolean entering=!roundabout(in)&&roundabout(out),leaving=roundabout(in)&&!roundabout(out);
            String target=road(out);double turn=angle(p.get(i-1),p.get(i),p.get(i+1));
            String code="",message="",junction="REGULAR";
            if(entering){code="ROUNDABOUT";message="Follow the highlighted route at the roundabout";junction="ROUNDABOUT";target="";}
            else if(leaving){code="ROUNDABOUT_EXIT";message="Exit the roundabout";junction="ROUNDABOUT";}
            else if(roundabout(in)&&roundabout(out))continue;
            else if(ways.get(i-1).equals(ways.get(i)))continue;
            else if(Math.abs(turn)>=150){code="CONTINUE";message="Follow the highlighted route; check road signs";}
            else if(Math.abs(turn)>=30&&(choices[i]>1||!road(in).equals(target))){
                code=turn<0?"TURN_LEFT":"TURN_RIGHT";message=turn<0?"Turn left":"Turn right";
            }else if(!target.isEmpty()&&!road(in).equals(target)){code="CONTINUE";message="Continue";}
            else continue;
            if(!target.isEmpty())message+=" onto "+target;
            result.add(new TomTomApi.Instruction(d[i],message,code,junction,0,turn,target,"","",ref(out),"LEFT"));
        }
        result.add(new TomTomApi.Instruction(d[d.length-1],"End of mapped route; check destination access","DESTINATION","REGULAR",0,0,"",""));
        return Collections.unmodifiableList(result);
    }
    static List<RouteIntelligence.SpeedSection> limits(List<GeoMath.Point> p,List<Long> ways,Map<Long,JSONObject> tags)throws IOException{
        validate(p,ways,tags);double[] d=offsets(p);List<RouteIntelligence.SpeedSection> out=new ArrayList<>();
        for(int i=0;i<ways.size();i++){
            int kmh=DriveMateProvider.explicitLimit(tags.get(ways.get(i)));
            if(kmh<=0)continue;
            if(!out.isEmpty()){RouteIntelligence.SpeedSection last=out.get(out.size()-1);
                if(last.kmh==kmh&&Math.abs(last.end-d[i])<.01){out.set(out.size()-1,new RouteIntelligence.SpeedSection(last.start,d[i+1],kmh));continue;}}
            out.add(new RouteIntelligence.SpeedSection(d[i],d[i+1],kmh));
        }
        return Collections.unmodifiableList(out);
    }
}
