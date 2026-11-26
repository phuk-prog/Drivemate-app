package com.drivemate.app;

import org.json.*;
import java.util.*;

/** Explicit directed OSM enforcement relation, never inferred from a nearby camera. */
final class MappedEnforcement {
    static final class Zone {
        final long relation;final double start,end;
        Zone(long relation,double start,double end){this.relation=relation;this.start=start;this.end=end;}
        boolean active(double progress){return Double.isFinite(progress)&&progress>=start&&progress<end;}
    }
    static final class Match {
        final List<RouteRoadsideAwareness.Event> notices=new ArrayList<>();
        final List<Zone> zones=new ArrayList<>();
    }
    static Match match(JSONArray relations,Map<Long,Double> nodes,Set<Long> ambiguous,List<Long> ways,double[] offsets)throws JSONException{
        Match result=new Match();
        if(relations==null||offsets==null||ways.size()+1!=offsets.length)return result;
        if(relations.length()>1000)throw new JSONException("Too many enforcement relations");
        Set<Long> seen=new HashSet<>();
        for(int i=0;i<relations.length();i++){
            JSONArray row=relations.optJSONArray(i);if(row==null||row.length()!=3)continue;
            long id=row.optLong(0,-1);if(id<=0||id>Long.MAX_VALUE/4||!seen.add(id))continue;
            JSONObject tags=row.optJSONObject(1);JSONArray members=row.optJSONArray(2);
            if(tags==null||members==null||members.length()>1000||!"enforcement".equals(tags.optString("type")))continue;
            String kind=tags.optString("enforcement");
            if(!Arrays.asList("maxspeed","average_speed","traffic_signals").contains(kind))continue;
            boolean conditional=false;
            Iterator<String> keys=tags.keys();while(keys.hasNext())if(keys.next().contains(":conditional"))conditional=true;
            if(conditional)continue;
            long from=-1,to=-1;boolean bad=false;Set<Long> devices=new HashSet<>();
            List<Long> section=new ArrayList<>();
            for(int j=0;j<members.length();j++){
                JSONArray m=members.optJSONArray(j);
                if(m==null||m.length()!=3){bad=true;break;}
                if("section".equals(m.optString(0))&&"w".equals(m.optString(1))){
                    long wayId=m.optLong(2,-1);if(wayId<=0||section.contains(wayId)){bad=true;break;}section.add(wayId);continue;
                }
                if(!"n".equals(m.optString(1))){bad=true;break;}
                long node=m.optLong(2,-1);if(node<=0){bad=true;break;}
                switch(m.optString(0)){
                    case "from":if(from!=-1)bad=true;else from=node;break;
                    case "to":if(to!=-1)bad=true;else to=node;break;
                    case "device":devices.add(node);break;
                    default:bad=true;break;
                }
            }
            if(bad||from<=0||devices.isEmpty())continue;
            boolean average="average_speed".equals(kind);
            if(average&&(to<=0||devices.size()<2))continue;
            if(!average&&!section.isEmpty())continue;
            if(to<=0&&devices.size()==1)to=devices.iterator().next();
            if(to<=0||from==to||ambiguous.contains(from)||ambiguous.contains(to))continue;
            Double start=nodes.get(from),end=nodes.get(to);
            if(start==null||end==null||!Double.isFinite(start)||!Double.isFinite(end)||end-start<3)continue;
            // Require exact directed anchors plus uninterrupted source-way identity.
            // Multi-way corridors require explicit section members in the same order.
            List<Long> traversed=new ArrayList<>();
            for(int j=0;j<ways.size();j++){
                if(offsets[j+1]<=start+.01||offsets[j]>=end-.01)continue;
                long way=ways.get(j);
                if(traversed.isEmpty()||traversed.get(traversed.size()-1)!=way)traversed.add(way);
            }
            if(traversed.isEmpty()||traversed.get(0)<=0)continue;
            if(section.isEmpty()?traversed.size()!=1:!section.equals(traversed))continue;
            if(average){
                result.zones.add(new Zone(id,start,end));
                result.notices.add(new RouteRoadsideAwareness.Event(-id*4,start,"Mapped average-speed zone starts"));
                result.notices.add(new RouteRoadsideAwareness.Event(-id*4-1,end,"Mapped average-speed zone ends"));
            }else{
                String label="traffic_signals".equals(kind)?"Mapped red-light camera":"Mapped speed camera";
                result.notices.add(new RouteRoadsideAwareness.Event(-id*4,start,label));
            }
        }
        return result;
    }
}
