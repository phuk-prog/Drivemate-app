package com.drivemate.app;

import org.json.*;
import java.util.*;

/** Conservative free-drive road identity; no nearest-road snapping or invented limits. */
final class FreeDriveRoadMatch {
    static final class Match {
        final long way; final int kmh; final String name;final int segment;final boolean forward;
        Match(long way,int kmh,String name,int segment,boolean forward){this.way=way;this.kmh=kmh;this.name=name;this.segment=segment;this.forward=forward;}
    }
    private long lastWay=-1,lastTime=-1; private int confirmations;private boolean lastForward;
    void reset(){lastWay=-1;lastTime=-1;confirmations=0;}
    Match confirm(Match m,long time){
        if(m==null){reset();return null;}
        if(time<=lastTime)return null;
        if(m.way!=lastWay||m.forward!=lastForward||lastTime<0||time-lastTime>5000){lastWay=m.way;lastForward=m.forward;confirmations=1;}
        else confirmations++;
        lastTime=time;
        return confirmations>=2?m:null;
    }
    static final class Upcoming {
        final long node;final int kmh;final double distance;
        Upcoming(long node,int kmh,double distance){this.node=node;this.kmh=kmh;this.distance=distance;}
    }
    private static GeoMath.Point point(JSONArray coords,int i)throws JSONException{
        JSONArray p=coords.getJSONArray(i);return new GeoMath.Point(p.getLong(0)/1e7,p.getLong(1)/1e7);
    }
    private static boolean directionAllowed(JSONObject tags,boolean forward){
        String one=tags.optString("oneway");
        if("yes".equals(one)||"1".equals(one)||"true".equals(one))return forward;
        if("-1".equals(one))return !forward;
        if("no".equals(one))return true;
        if(!one.isEmpty())return false;
        return forward||!"roundabout".equals(tags.optString("junction"))&&!"motorway".equals(tags.optString("highway"));
    }
    /** Only a same-named, sole mapped continuation with explicit limits.
     * This is an advisory if the driver continues, never a turn instruction.
     * Reject intermediate connected branches, missing data, loops and conditional directions. */
    static Upcoming upcoming(List<JSONArray> ways,Match current,GeoMath.Point gps,double speed,double[] bounds)throws JSONException{
        if(current==null||ways==null||bounds==null||bounds.length!=4||!Double.isFinite(speed)||speed<3)return null;
        JSONArray origin=null;
        for(JSONArray row:ways)if(row.getLong(0)==current.way){origin=row;break;}
        if(origin==null)return null;
        JSONObject tags=origin.getJSONObject(1);
        String name=tags.optString("name"),ref=tags.optString("ref");
        if(name.isEmpty()&&ref.isEmpty())return null;
        if(tags.has("oneway:conditional")||tags.has("access:conditional"))return null;
        JSONArray coords=origin.getJSONArray(2),refs=origin.getJSONArray(3);
        if(refs.length()!=coords.length()||current.segment<0||current.segment+1>=refs.length())return null;
        HashSet<Long> remaining=new HashSet<>();
        int step=current.forward?1:-1,index=current.forward?current.segment+1:current.segment;
        int end=current.forward?refs.length()-1:0;
        double distance=GeoMath.distance(gps,point(coords,index));
        for(int i=index;;i+=step){
            if(!remaining.add(refs.getLong(i)))return null;
            if(i==end)break;distance+=GeoMath.distance(point(coords,i),point(coords,i+step));
        }
        double horizon=Math.max(90,Math.min(450,speed*12+40));
        if(distance<10||distance>horizon)return null;
        long node=refs.getLong(end);
        GeoMath.Point boundary=point(coords,end);
        if(boundary.lon<=bounds[0]+.0007||boundary.lon>=bounds[2]-.0007||boundary.lat<=bounds[1]+.0005||boundary.lat>=bounds[3]-.0005)return null;
        JSONArray continuation=null;boolean forward=false;
        for(JSONArray row:ways){
            if(row==origin||row.getLong(0)==current.way||row.getJSONObject(1).optString("highway").isEmpty())continue;
            JSONArray ids=row.getJSONArray(3);
            for(int i=0;i<ids.length();i++){
                long id=ids.getLong(i);if(!remaining.contains(id))continue;
                if(id!=node||continuation!=null||ids.length()<2||(i!=0&&i!=ids.length()-1))return null;
                continuation=row;forward=i==0;
            }
        }
        if(continuation==null)return null;
        JSONObject next=continuation.getJSONObject(1);JSONArray nextCoords=continuation.getJSONArray(2);
        if(nextCoords.length()<2||nextCoords.length()!=continuation.getJSONArray(3).length()
            ||GeoMath.distance(boundary,point(nextCoords,forward?0:nextCoords.length()-1))>.2)return null;
        if((name.isEmpty()||!name.equals(next.optString("name")))&&(ref.isEmpty()||!ref.equals(next.optString("ref"))))return null;
        if(next.has("access:conditional")||next.has("oneway:conditional")||!directionAllowed(next,forward))return null;
        int limit=DriveMateProvider.explicitLimit(next);
        if(limit<=0||limit==current.kmh)return null;
        return new Upcoming(node,limit,distance);
    }
    static Match match(List<JSONArray> ways,GeoMath.Point gps,double accuracy,double bearing,double speed)throws JSONException{
        if(ways==null||gps==null||!Double.isFinite(gps.lat)||!Double.isFinite(gps.lon)
            ||!Double.isFinite(accuracy)||accuracy<=0||accuracy>10||!Double.isFinite(bearing)||!Double.isFinite(speed)||speed<3)return null;
        long chosen=-1;JSONObject chosenTags=null;double closest=Double.POSITIVE_INFINITY;int chosenSegment=-1;boolean chosenForward=false;
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
                double forward=Math.abs((bearing-heading+540)%360-180),reverse=Math.abs((bearing-heading-180+540)%360-180);
                String one=tags.optString("oneway");
                boolean f=one.isEmpty()||"no".equals(one)||"yes".equals(one)||"1".equals(one)||"true".equals(one);
                boolean r="no".equals(one)||"-1".equals(one)||one.isEmpty()&&!"roundabout".equals(tags.optString("junction"))&&!"motorway".equals(highway);
                if(!(f&&forward<=25||r&&reverse<=25))continue;
                chosen=id;chosenTags=tags;closest=g.offRouteMetres;chosenSegment=i-1;chosenForward=f&&forward<=25;
            }
        }
        if(chosenTags==null)return null;
        // Do not accept a farther same-way segment simply because its heading fits.
        if(distances.get(chosen)<closest-2)return null;
        for(Map.Entry<Long,Double> other:distances.entrySet())if(other.getKey()!=chosen&&other.getValue()<=closest+accuracy+5)return null;
        int limit=DriveMateProvider.explicitLimit(chosenTags);
        if(limit<=0||chosenTags.has("access:conditional")||chosenTags.has("oneway:conditional"))return null;
        return new Match(chosen,limit,chosenTags.optString("name",""),chosenSegment,chosenForward);
    }
}
