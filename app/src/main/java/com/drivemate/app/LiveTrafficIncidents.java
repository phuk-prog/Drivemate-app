package com.drivemate.app;

import org.json.*;
import java.time.Instant;
import java.util.*;

/** TomTom Traffic Incident Details v5. Area events are not route/direction proof. */
final class LiveTrafficIncidents {
    static final long MAX_AGE_MS=180000;
    static final class Event {
        final String id,label;final int category;final long endAt;
        final List<GeoMath.Point> points;
        Event(String id,String label,int category,long endAt,List<GeoMath.Point> points){
            this.id=id;this.label=label;this.category=category;this.endAt=endAt;this.points=Collections.unmodifiableList(points);
        }
        boolean present(long now){return endAt==0||now<endAt;}
    }
    static String label(int n){
        switch(n){
            case 1:return "Accident";case 2:return "Fog";case 3:return "Dangerous conditions";
            case 4:return "Heavy rain";case 5:return "Ice";case 6:return "Congestion";
            case 7:return "Lane closed";case 8:return "Road closed";case 9:return "Roadworks";
            case 10:return "Wind";case 11:return "Flooding";case 14:return "Broken-down vehicle";
            default:return "";
        }
    }
    static List<Event> parse(JSONObject response,long now)throws JSONException{
        JSONArray rows=response.getJSONArray("incidents");
        if(rows.length()>1000)throw new JSONException("Incident response exceeds bounds");
        ArrayList<Event> result=new ArrayList<>();HashSet<String> ids=new HashSet<>();int vertices=0;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null||!"Feature".equals(row.optString("type")))continue;
            JSONObject p=row.optJSONObject("properties"),g=row.optJSONObject("geometry");if(p==null||g==null)continue;
            if(!"present".equals(p.optString("timeValidity")))continue;
            String id=p.optString("id",""),label=label(p.optInt("iconCategory",-1));
            if(id.isEmpty()||id.length()>128||label.isEmpty()||ids.contains(id))continue;
            long end=0;
            if(p.has("endTime")&&!p.isNull("endTime")){
                try{end=Instant.parse(p.getString("endTime")).toEpochMilli();}catch(Exception invalid){continue;}
                if(end<=now)continue;
            }
            JSONArray coords=g.optJSONArray("coordinates");if(coords==null)continue;
            ArrayList<GeoMath.Point> points=new ArrayList<>();
            if("Point".equals(g.optString("type"))){GeoMath.Point a=point(coords);if(a!=null)points.add(a);}
            else if("LineString".equals(g.optString("type"))&&coords.length()>=2&&coords.length()<=5000){
                for(int j=0;j<coords.length();j++){GeoMath.Point a=point(coords.optJSONArray(j));if(a==null){points.clear();break;}points.add(a);}
            }
            if(points.isEmpty())continue;
            vertices+=points.size();if(vertices>30000)throw new JSONException("Incident geometry exceeds bounds");
            ids.add(id);result.add(new Event(id,label,p.optInt("iconCategory"),end,points));
        }
        return Collections.unmodifiableList(result);
    }
    private static GeoMath.Point point(JSONArray c){
        if(c==null||c.length()!=2)return null;
        double x=c.optDouble(0,Double.NaN),y=c.optDouble(1,Double.NaN);
        return Double.isFinite(x)&&Double.isFinite(y)&&x>=-180&&x<=180&&y>=-90&&y<=90?new GeoMath.Point(y,x):null;
    }
}
