package com.drivemate.app;
import org.json.*;
import java.util.*;

/** Preview only: original maneuver candidates and conservative road metadata.
 * Never used for live driving until topology, lane and road acceptance passes. */
public final class DmGuidance {
    private DmGuidance(){}
    public static final class Step {
        public final double metres;public final String instruction;public final long way;
        Step(double m,String i,long w){metres=m;instruction=i;way=w;}
    }
    public static List<Step> preview(List<GeoMath.Point> points,List<Long> ways,Map<Long,JSONObject> tags){
        List<Step> out=new ArrayList<>();if(points.size()<2||ways.size()!=points.size()-1)return out;
        double travelled=0;
        for(int i=1;i<points.size()-1;i++){
            travelled+=GeoMath.distance(points.get(i-1),points.get(i));
            long from=ways.get(i-1),to=ways.get(i);if(from==to)continue;
            JSONObject a=tags.get(from),b=tags.get(to);if(a==null||b==null)continue;
            if("roundabout".equals(a.optString("junction"))||"roundabout".equals(b.optString("junction"))||"circular".equals(a.optString("junction"))||"circular".equals(b.optString("junction")))continue;
            GeoMath.Point prev=points.get(i-1),at=points.get(i),next=points.get(i+1);
            if(GeoMath.distance(prev,at)<4||GeoMath.distance(at,next)<4)continue;
            double turn=(GeoMath.bearingDegrees(at,next)-GeoMath.bearingDegrees(prev,at)+540)%360-180;
            if(Math.abs(turn)<35||Math.abs(turn)>150)continue;
            String road=b.optString("name",b.optString("ref","")).trim();
            if(road.isEmpty())continue;
            out.add(new Step(travelled,(turn<0?"Possible left onto ":"Possible right onto ")+road,to));
        }
        out.add(new Step(GeoMath.length(points),"End of preview",ways.get(ways.size()-1)));
        return Collections.unmodifiableList(out);
    }
    public static Integer explicitMph(JSONObject tags){
        if(tags==null)return null;
        for(Iterator<String> i=tags.keys();i.hasNext();){String k=i.next();if(k.startsWith("maxspeed:")&&!k.equals("maxspeed:type")&&!k.equals("maxspeed:source"))return null;}
        String s=tags.optString("maxspeed","").trim().toLowerCase(Locale.ROOT);
        if(!s.matches("[0-9]{1,3} mph"))return null;
        int mph=Integer.parseInt(s.substring(0,s.indexOf(' ')));
        return mph>=5&&mph<=70?mph:null;
    }
    /** Preserve source tags; do not manufacture selected lane directions. */
    public static String mappedLaneDescription(JSONObject tags){
        if(tags==null)return "";
        String lanes=tags.optString("lanes",""),turn=tags.optString("turn:lanes","");
        if(!"yes".equals(tags.optString("oneway"))||!lanes.matches("[1-8]")||turn.isEmpty()||turn.length()>256)return "";
        for(Iterator<String> i=tags.keys();i.hasNext();){String k=i.next();if(k.startsWith("turn:lanes:")||k.startsWith("lanes:")||k.startsWith("oneway:"))return "";}
        String[] values=turn.split("\\|",-1);if(values.length!=Integer.parseInt(lanes))return "";
        for(String lane:values)if(!lane.matches("(left|right|through|slight_left|slight_right|sharp_left|sharp_right|reverse|merge_to_left|merge_to_right|none)(;(left|right|through|slight_left|slight_right|sharp_left|sharp_right|reverse|merge_to_left|merge_to_right|none))*"))return "";
        return "Mapped lane tags: "+turn;
    }
}
