package com.drivemate.app;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Exact directed route associations; no proximity-derived safety warnings. */
final class NativeRouteIntelligence {
    static final class Result {
        final List<RouteRoadsideAwareness.Event> events;
        final List<MappedEnforcement.Zone> zones;
        Result(List<RouteRoadsideAwareness.Event> e,List<MappedEnforcement.Zone> z){events=Collections.unmodifiableList(new ArrayList<>(e));zones=Collections.unmodifiableList(new ArrayList<>(z));}
    }
    static Result match(List<GeoMath.Point> points,List<Long> nodes,List<Long> ways,JSONArray roadside,JSONArray enforcement)throws Exception{
        if(points.size()!=nodes.size()||ways.size()+1!=nodes.size()||nodes.size()<2)throw new IOException("Route intelligence identity mismatch");
        Map<Long,Double> offsets=new HashMap<>();Map<Long,GeoMath.Point> coords=new HashMap<>();Set<Long> ambiguous=new HashSet<>();
        double[] d=new double[points.size()];
        for(int i=0;i<points.size();i++){if(i>0)d[i]=d[i-1]+GeoMath.distance(points.get(i-1),points.get(i));long id=nodes.get(i);Double old=offsets.putIfAbsent(id,d[i]);if(old!=null&&Math.abs(old-d[i])>1)ambiguous.add(id);coords.putIfAbsent(id,points.get(i));}
        List<RouteRoadsideAwareness.Event> events=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(int i=0;i<roadside.length();i++){
            JSONArray row=roadside.getJSONArray(i);long id=row.getLong(0);if(!offsets.containsKey(id)||ambiguous.contains(id)||!seen.add(id))continue;
            GeoMath.Point p=coords.get(id);if(row.getLong(1)!=Math.round(p.lat*1e6)||row.getLong(2)!=Math.round(p.lon*1e6))continue;
            JSONObject t=row.getJSONObject(3);String direction=t.optString("traffic_signals:direction",t.optString("direction",""));
            boolean conditional=false;Iterator<String> keys=t.keys();while(keys.hasNext()){String k=keys.next();if(k.contains(":conditional")||k.contains(":forward")||k.contains(":backward"))conditional=true;}
            String label=RouteRoadsideAwareness.label(t.optString("highway"),t.optString("traffic_calming"),direction,conditional,t.optString("crossing_ref"),t.optString("crossing"),t.optString("crossing:signals"));
            if(!label.isEmpty())events.add(new RouteRoadsideAwareness.Event(id,offsets.get(id),label));
        }
        MappedEnforcement.Match verified=MappedEnforcement.match(enforcement,offsets,ambiguous,ways,d);
        events.addAll(verified.notices);events.sort(Comparator.comparingDouble(e->e.offset));return new Result(events,verified.zones);
    }
}
