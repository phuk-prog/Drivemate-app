package com.drivemate.app;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Hosted independent OSM route and search adapter. No TomTom calls. */
public final class DriveMateProvider {
    private DriveMateProvider(){}
    public static boolean valid(String value){
        try{URL u=new URL(value);return "https".equals(u.getProtocol())&&!u.getHost().isEmpty()&&u.getUserInfo()==null&&u.getQuery()==null&&u.getRef()==null;}
        catch(Exception e){return false;}
    }
    private static JSONObject json(String url,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(20000);
        try{
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            if(c.getResponseCode()!=200)throw new IOException("DriveMate service unavailable ("+c.getResponseCode()+")");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>8000000)throw new IOException("Response too large");out.write(buf,0,n);}
                return new JSONObject(out.toString("UTF-8"));}
        }finally{c.disconnect();}
    }
    private static JSONObject point(GeoMath.Point p)throws JSONException{return new JSONObject().put("lat",p.lat).put("lon",p.lon);}
    public static TomTomApi.Route route(String endpoint,GeoMath.Point from,GeoMath.Point to)throws Exception{
        if(!valid(endpoint)||!endpoint.endsWith("/route"))throw new IOException("Configure trusted DriveMate HTTPS /route endpoint");
        JSONObject req=new JSONObject().put("locations",new JSONArray().put(point(from)).put(point(to))).put("costing","auto").put("units","kilometers").put("language","en-GB").put("turn_lanes",true);
        JSONObject response=json(endpoint,req);TomTomApi.Route route=parse(response);
        try{
            String shape=response.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getString("shape");
            JSONObject trace=new JSONObject().put("encoded_polyline",shape).put("shape_match","edge_walk").put("costing","auto").put("units","kilometers")
                .put("filters",new JSONObject().put("action","include").put("attributes",new JSONArray().put("shape").put("edge.speed_limit").put("edge.way_id").put("edge.begin_shape_index").put("edge.end_shape_index")));
            String root=endpoint.substring(0,endpoint.length()-6);
            JSONObject graph=json(root+"/v1/graph-source",null),meta=json(root+"/v1/meta",null);
            String source=graph.optString("source_sha256");
            if(!source.matches("[a-f0-9]{64}") || !source.equals(meta.optString("source_sha256")) || !"uk".equals(meta.optString("scope")))return route;
            JSONObject traced=json(root+"/trace_attributes",trace);
            Map<Long,JSONObject> tags=new HashMap<>();Set<Long> ids=new LinkedHashSet<>();
            JSONArray edges=traced.getJSONArray("edges");
            for(int i=0;i<edges.length();i++){long id=edges.getJSONObject(i).optLong("way_id",0);if(id>0)ids.add(id);}
            if(ids.size()>3000)return route;
            List<Long> all=new ArrayList<>(ids);
            for(int i=0;i<all.size();i+=60){
                StringBuilder query=new StringBuilder();
                for(long id:all.subList(i,Math.min(i+60,all.size()))){if(query.length()>0)query.append(',');query.append(id);}
                JSONObject batch=json(root+"/v1/ways?ids="+query,null);
                if(!source.equals(batch.optString("source_sha256")))return route;
                JSONArray ways=batch.getJSONArray("ways");
                for(int j=0;j<ways.length();j++){JSONObject way=ways.getJSONObject(j);tags.put(way.getLong("id"),way.getJSONObject("tags"));}
            }
            if(!source.equals(json(root+"/v1/graph-source",null).optString("source_sha256")))return route;
            route.speedSections.addAll(verifiedSpeedSections(traced,route.points,tags));
            List<Long> verifiedWays = exactTraceWays(traced, route.points);
            if (!verifiedWays.isEmpty()) { route.geometrySourceSha=source; route.geometryWays=verifiedWays; }
        }catch(Exception ignored){/* No explicit aligned limit: unknown. */}
        return route;
    }
    /** Optional V2 geometry from the configured trusted routing host; never alters route or instructions. */
    static DmUnifiedGeometry sharedMap(String endpoint,TomTomApi.Route route){
        if(!valid(endpoint)||!endpoint.endsWith("/route")||route.geometryWays.isEmpty()||!route.geometrySourceSha.matches("[a-f0-9]{64}"))return null;
        double[] b=RouteMapPolicy.corridorBounds(route.points);if(b==null)return null;
        try{
            String bbox=String.format(Locale.US,"%.8f,%.8f,%.8f,%.8f",b[0],b[1],b[2],b[3]);
            JSONObject result=json(endpoint.substring(0,endpoint.length()-6)+"/v2/roads?bbox="+bbox+"&source="+route.geometrySourceSha,null);
            if(!"drivemate-map-v2".equals(result.optString("format"))||result.optInt("coordinate_scale")!=10000000||!route.geometrySourceSha.equals(result.optString("source_sha256")))return null;
            JSONArray bounds=result.getJSONArray("bounds");if(bounds.length()!=4)return null;
            for(int i=0;i<4;i++)if(Math.abs(bounds.getDouble(i)-b[i])>1e-7)return null;
            JSONArray input=result.getJSONArray("features");if(input.length()>6000)return null;
            List<JSONArray> features=new ArrayList<>();int vertices=0;
            for(int i=0;i<input.length();i++){JSONArray feature=input.getJSONArray(i);vertices+=feature.getJSONArray(2).length();if(vertices>100000)return null;features.add(feature);}
            DmUnifiedGeometry geometry=new DmUnifiedGeometry(features,route.points,route.geometryWays,b);
            JSONArray mapped=result.optJSONArray("points");if(mapped!=null)geometry.setMappedPoints(mapped);
            return geometry;
        }catch(Exception failure){return null;}
    }
    /** Require identical encoded vertices and uninterrupted way identity for every segment. */
    static List<Long> exactTraceWays(JSONObject trace, List<GeoMath.Point> route)throws Exception {
        List<GeoMath.Point> shape=decode(trace.getString("shape"));
        if(!RouteMapPolicy.samePoints(shape,route))return Collections.emptyList();
        List<Long> ways=new ArrayList<>(); JSONArray edges=trace.getJSONArray("edges"); int next=0;
        for(int i=0;i<edges.length();i++){
            JSONObject e=edges.getJSONObject(i); int a=e.optInt("begin_shape_index",-1),b=e.optInt("end_shape_index",-1); long id=e.optLong("way_id",0);
            if(id<=0||a!=next||b<=a||b>=shape.size())return Collections.emptyList();
            while(next<b){ways.add(id);next++;}
        }
        return next==shape.size()-1?Collections.unmodifiableList(ways):Collections.emptyList();
    }
    static List<GeoMath.Point> decode(String s)throws IOException{
        List<GeoMath.Point> out=new ArrayList<>();int i=0;long lat=0,lon=0;
        while(i<s.length()){long[] d=new long[2];for(int axis=0;axis<2;axis++){
            long n=0;int shift=0,k;do{if(i>=s.length()||shift>30)throw new IOException("Invalid route shape");k=s.charAt(i++)-63;if(k<0||k>63)throw new IOException("Invalid route shape");n|=(long)(k&31)<<shift;shift+=5;}while(k>=32);d[axis]=(n&1)!=0?~(n>>1):n>>1;}
            lat+=d[0];lon+=d[1];if(Math.abs(lat)>90000000||Math.abs(lon)>180000000||out.size()>200000)throw new IOException("Invalid shape");
            out.add(new GeoMath.Point(lat/1e6,lon/1e6));}
        if(out.size()<2)throw new IOException("Empty shape");return out;
    }
    static double[] offsets(List<GeoMath.Point> path){double[] a=new double[path.size()];for(int i=1;i<a.length;i++)a[i]=a[i-1]+GeoMath.distance(path.get(i-1),path.get(i));return a;}
    static TomTomApi.Route parse(JSONObject response)throws Exception{
        JSONObject trip=response.getJSONObject("trip");if(trip.optInt("status",-1)!=0||!"kilometers".equals(trip.optString("units")))throw new IOException("Invalid route result");
        JSONArray legs=trip.getJSONArray("legs");if(legs.length()!=1)throw new IOException("Unsupported legs");
        JSONObject leg=legs.getJSONObject(0);List<GeoMath.Point> points=decode(leg.getString("shape"));double[] offset=offsets(points);
        List<TomTomApi.Instruction> steps=new ArrayList<>();List<RouteIntelligence.LaneSection> lanes=new ArrayList<>();
        JSONArray moves=leg.getJSONArray("maneuvers");int previous=-1;
        for(int i=0;i<moves.length();i++){JSONObject move=moves.getJSONObject(i);int index=move.getInt("begin_shape_index"),type=move.getInt("type");
            if(index<previous||index<0||index>=points.size())throw new IOException("Invalid maneuver index");previous=index;
            String msg=move.getString("instruction");if(msg.trim().isEmpty())throw new IOException("Empty maneuver");
            if(type>=1&&type<=3)continue;
            String code=maneuver(type),junction=junctionType(type);
            JSONArray names=move.optJSONArray("street_names");String street=names==null?"":names.optString(0,"");
            steps.add(new TomTomApi.Instruction(offset[index],msg,code,junction,move.optInt("roundabout_exit_count",0),code.contains("LEFT")?-45:code.contains("RIGHT")?45:0,street,sign(move)));
            RouteIntelligence.LaneSection s=laneSection(move.optJSONArray("lanes"),offset[index]);if(s!=null)lanes.add(s);
        }
        double total=offset[offset.length-1];int seconds=(int)Math.round(trip.getJSONObject("summary").getDouble("time"));
        if(steps.isEmpty()||total<=0||seconds<=0)throw new IOException("Incomplete navigation route");
        return new TomTomApi.Route(points,steps,total,seconds,0,lanes,new ArrayList<>(),new ArrayList<>(),"DriveMate");
    }
    static String maneuver(int type){
        switch(type){
            case 4:case 5:case 6:return "DESTINATION";case 9:return "SLIGHT_RIGHT";case 10:return "RIGHT";case 11:return "SHARP_RIGHT";
            case 12:return "RIGHT_U_TURN";case 13:return "LEFT_U_TURN";case 14:return "SHARP_LEFT";case 15:return "LEFT";case 16:return "SLIGHT_LEFT";
            case 17:return "RAMP_STRAIGHT";case 18:return "RAMP_RIGHT";case 19:return "RAMP_LEFT";case 20:return "MOTORWAY_EXIT_RIGHT";case 21:return "MOTORWAY_EXIT_LEFT";case 22:return "STRAIGHT";case 23:return "KEEP_RIGHT";case 24:return "KEEP_LEFT";case 25:return "MERGE";case 37:return "MERGE_RIGHT";case 38:return "MERGE_LEFT";case 26:return "ROUNDABOUT";case 27:return "ROUNDABOUT_EXIT";case 7:case 8:case 28:case 29:return "STRAIGHT";default:throw new IllegalArgumentException("Unsupported Valhalla maneuver "+type);}
    }
    /** Valhalla documented manoeuvre enum: ramp/exit are not bifurcations. */
    static String junctionType(int type){return type>=22&&type<=24?"BIFURCATION":type==26||type==27?"ROUNDABOUT":"";}
    static String sign(JSONObject move){
        JSONObject sign=move.optJSONObject("sign");if(sign==null)return "";
        List<String> labels=new ArrayList<>();
        for(String key:new String[]{"exit_number_elements","exit_branch_elements","exit_toward_elements","exit_name_elements"}){
            JSONArray list=sign.optJSONArray(key);if(list==null)continue;
            for(int i=0;i<list.length();i++){JSONObject entry=list.optJSONObject(i);if(entry!=null&&!entry.optString("text").isEmpty())labels.add(entry.optString("text"));}
        }
        return String.join(" · ",labels);
    }
    static RouteIntelligence.LaneSection laneSection(JSONArray a,double along)throws JSONException{
        if(a==null||a.length()==0||along<5)return null;List<RouteIntelligence.Lane> lanes=new ArrayList<>();
        int[] bits={2,4,8,16,32,64,128,256,512,1024};String[] names={"STRAIGHT","SHARP_LEFT","LEFT","SLIGHT_LEFT","SLIGHT_RIGHT","RIGHT","SHARP_RIGHT","UTURN","SLIGHT_LEFT","SLIGHT_RIGHT"};
        for(int i=0;i<a.length();i++){JSONObject lane=a.optJSONObject(i);if(lane==null)return null;
            int mask=lane.optInt("directions",0),active=lane.optInt("active",0);
            if(mask<=0||(mask&~2046)!=0||(active&~mask)!=0||Integer.bitCount(active)>1)return null;
            List<String> directions=new ArrayList<>();String recommended="";
            for(int d=0;d<bits.length;d++)if((mask&bits[d])!=0){if(!directions.contains(names[d]))directions.add(names[d]);if((active&bits[d])!=0)recommended=names[d];}
            if(directions.isEmpty())return null;lanes.add(new RouteIntelligence.Lane(recommended.isEmpty()?directions.get(0):recommended,!recommended.isEmpty(),directions));}
        RouteIntelligence.LaneSection s=new RouteIntelligence.LaneSection(Math.max(0,along-100),along+2,lanes);return s.trusted()?s:null;
    }
    /** Only explicit symmetric numeric limits, no directional/conditional/variable guesses. */
    static int explicitLimit(JSONObject tags){
        if(tags==null)return 0;
        Iterator<String> keys=tags.keys();
        while(keys.hasNext()){
            String k=keys.next();
            if(k.startsWith("maxspeed:") && !k.equals("maxspeed:type") && !k.equals("maxspeed:source"))return 0;
        }
        String raw=tags.optString("maxspeed","").trim().toLowerCase(Locale.UK);
        if(!raw.matches("[0-9]{1,3}(?:\\s*(?:mph|km/h|kmh))?"))return 0;
        try{boolean mph=raw.endsWith("mph");int v=Integer.parseInt(raw.replaceAll("[^0-9]",""));
            int kmh=mph?(int)Math.round(v*1.609344):v;return kmh>=5&&kmh<=140?kmh:0;
        }catch(NumberFormatException e){return 0;}
    }
    static List<RouteIntelligence.SpeedSection> verifiedSpeedSections(JSONObject trace,List<GeoMath.Point> route,Map<Long,JSONObject> tags)throws Exception{
        List<RouteIntelligence.SpeedSection> result=new ArrayList<>();List<GeoMath.Point> matched=decode(trace.getString("shape"));
        if(matched.size()!=route.size())return result;for(int i=0;i<route.size();i++)if(GeoMath.distance(route.get(i),matched.get(i))>1.5)return result;
        double[] d=offsets(route);JSONArray edges=trace.getJSONArray("edges");
        for(int i=0;i<edges.length();i++){
            JSONObject e=edges.getJSONObject(i);long id=e.optLong("way_id",0);int speed=explicitLimit(tags.get(id));
            int reported=e.optInt("speed_limit",0),a=e.optInt("begin_shape_index",-1),b=e.optInt("end_shape_index",-1);
            if(speed>0&&Math.abs(speed-reported)<=1&&a>=0&&b>a&&b<d.length&&d[b]>d[a]){
                if(!result.isEmpty()){RouteIntelligence.SpeedSection last=result.get(result.size()-1);
                    if(last.kmh==speed&&Math.abs(last.end-d[a])<0.01){result.set(result.size()-1,new RouteIntelligence.SpeedSection(last.start,d[b],speed));continue;}}
                result.add(new RouteIntelligence.SpeedSection(d[a],d[b],speed));
            }
        }
        return result;
    }
    public static List<TomTomApi.Destination> search(String endpoint,String query,GeoMath.Point near)throws Exception{
        if(!valid(endpoint))throw new IOException("Configure trusted DriveMate Photon HTTPS search endpoint");
        String url=endpoint+"?q="+URLEncoder.encode(query,"UTF-8")+"&limit=6";if(near!=null)url+="&lat="+near.lat+"&lon="+near.lon;
        JSONArray features=json(url,null).getJSONArray("features");List<TomTomApi.Destination> out=new ArrayList<>();
        for(int i=0;i<features.length();i++){JSONObject feature=features.getJSONObject(i),prop=feature.getJSONObject("properties");JSONArray xy=feature.getJSONObject("geometry").getJSONArray("coordinates");
            double lat=xy.getDouble(1),lon=xy.getDouble(0);if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat<49||lat>61||lon< -9||lon>3)continue;
            String name=prop.optString("name",prop.optString("street",query)),address=String.join(" ",prop.optString("housenumber"),prop.optString("street"),prop.optString("city"),prop.optString("postcode")).trim();
            out.add(new TomTomApi.Destination(name,address,new GeoMath.Point(lat,lon)));}return out;
    }
}
