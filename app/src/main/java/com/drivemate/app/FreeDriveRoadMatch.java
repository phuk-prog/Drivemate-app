package com.drivemate.app;

import org.json.*;
import java.util.*;

/** Conservative free-drive road identity; no nearest-road snapping or invented limits. */
final class FreeDriveRoadMatch {
    static final class Match {
        final long way; final int kmh; final String name;
        Match(long way,int kmh,String name){this.way=way;this.kmh=kmh;this.name=name;}
    }
    private long lastWay=-1,lastTime=-1; private int confirmations;
    void reset(){lastWay=-1;lastTime=-1;confirmations=0;}
    Match confirm(Match m,long time){
        if(m==null){reset();return null;}
        if(time<=lastTime)return null;
        if(m.way!=lastWay||lastTime<0||time-lastTime>5000){lastWay=m.way;confirmations=1;}
        else confirmations++;
        lastTime=time;
        return confirmations>=2?m:null;
    }
    static Match match(List<JSONArray> ways,GeoMath.Point gps,double accuracy,double bearing,double speed)throws JSONException{
        if(ways==null||gps==null||!Double.isFinite(gps.lat)||!Double.isFinite(gps.lon)
            ||!Double.isFinite(accuracy)||accuracy<=0||accuracy>10||!Double.isFinite(bearing)||!Double.isFinite(speed)||speed<3)return null;
        long chosen=-1;JSONObject chosenTags=null;double closest=Double.POSITIVE_INFINITY;
        // All highway candidates, including unknown-limit roads, can make a fix ambiguous.
        // Do not discard a competing carriageway solely because its heading differs.
        HashMap<Long,Double> distances=new HashMap<>();
        for(JSONArray row:ways){
            if(row.length()!=4)continue;
            JSONObject tags=row.getJSONObject(1);String highway=tags.optString("highway");
            if(highway.isEmpty()||"yes".equals(tags.optString("area")))continue;
            if(Arrays.asList("footway","cycleway","path","steps","bridleway","pedestrian","construction","proposed","platform").contains(highway))continue;
            long id=row.getLong(0);JSONArray coords=row.getJSONArray(2);
            for(int i=1;i<coords.length();i++){
                JSONArray a=coords.getJSONArray(i-1),b=coords.getJSONArray(i);
                GeoMath.Point p=new GeoMath.Point(a.getLong(0)/1e7,a.getLong(1)/1e7),q=new GeoMath.Point(b.getLong(0)/1e7,b.getLong(1)/1e7);
                if(GeoMath.distance(p,q)<1)continue;
                GeoMath.Progress g=GeoMath.project(Arrays.asList(p,q),gps);
                if(g.offRouteMetres>accuracy+12)continue;
                distances.put(id,Math.min(distances.getOrDefault(id,Double.POSITIVE_INFINITY),g.offRouteMetres));
                if(g.offRouteMetres>Math.min(7,accuracy)||g.offRouteMetres>=closest)continue;
                double heading=GeoMath.bearingDegrees(p,q);
                double forward=Math.abs((bearing-heading+540)%360-180),reverse=Math.abs((bearing-heading-180+720)%360-180);
                String one=tags.optString("oneway");
                boolean f=one.isEmpty()||"no".equals(one)||"yes".equals(one)||"1".equals(one)||"true".equals(one);
                boolean r="no".equals(one)||"-1".equals(one)||one.isEmpty()&&!"roundabout".equals(tags.optString("junction"))&&!"motorway".equals(highway);
                if(!(f&&forward<=25||r&&reverse<=25))continue;
                chosen=id;chosenTags=tags;closest=g.offRouteMetres;
            }
        }
        if(chosenTags==null)return null;
        for(Map.Entry<Long,Double> other:distances.entrySet())if(other.getKey()!=chosen&&other.getValue()<=closest+accuracy+5)return null;
        int limit=DriveMateProvider.explicitLimit(chosenTags);
        if(limit<=0||chosenTags.has("access:conditional")||chosenTags.has("oneway:conditional"))return null;
        return new Match(chosen,limit,chosenTags.optString("name",""));
    }
}
