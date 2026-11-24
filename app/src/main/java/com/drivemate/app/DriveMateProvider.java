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
                .put("filters",new JSONObject().put("action","include").put("attributes",new JSONArray().put("shape").put("edge.speed_limit").put("edge.begin_shape_index").put("edge.end_shape_index")));
            route.speedSections.addAll(speedSections(json(endpoint.substring(0,endpoint.length()-6)+"/trace_attributes",trace),route.points));
        }catch(Exception ignored){/* No explicit aligned limit: unknown. */}
        return route;
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
            String code=maneuver(type),junction=type>=20&&type<=22?"BIFURCATION":type==26||type==27?"ROUNDABOUT":"";
            JSONArray names=move.optJSONArray("street_names");String street=names==null?"":names.optString(0,"");
            steps.add(new TomTomApi.Instruction(offset[index],msg,code,junction,move.optInt("roundabout_exit_count",0),code.contains("LEFT")?-45:code.contains("RIGHT")?45:0,street,""));
            RouteIntelligence.LaneSection s=laneSection(move.optJSONArray("lanes"),offset[index]);if(s!=null)lanes.add(s);
        }
        double total=offset[offset.length-1];int seconds=(int)Math.round(trip.getJSONObject("summary").getDouble("time"));
        if(steps.isEmpty()||total<=0||seconds<=0)throw new IOException("Incomplete navigation route");
        return new TomTomApi.Route(points,steps,total,seconds,0,lanes,new ArrayList<>(),new ArrayList<>(),"DriveMate");
    }
    static String maneuver(int type){
        switch(type){
            case 4:case 5:case 6:return "DESTINATION";case 9:return "SLIGHT_RIGHT";case 10:return "RIGHT";case 11:return "SHARP_RIGHT";
            case 12:case 13:return "UTURN";case 14:return "SHARP_LEFT";case 15:return "LEFT";case 16:return "SLIGHT_LEFT";
            case 18:case 20:case 22:return "RIGHT";case 19:case 21:case 23:return "LEFT";case 26:case 27:return "ROUNDABOUT";default:return "STRAIGHT";}
    }
    static RouteIntelligence.LaneSection laneSection(JSONArray a,double along)throws JSONException{
        if(a==null||a.length()==0||along<5)return null;List<RouteIntelligence.Lane> lanes=new ArrayList<>();
        int[] bits={2,4,8,16,32,64,128,256};String[] names={"STRAIGHT","SHARP_LEFT","LEFT","SLIGHT_LEFT","SLIGHT_RIGHT","RIGHT","SHARP_RIGHT","UTURN"};
        for(int i=0;i<a.length();i++){JSONObject lane=a.optJSONObject(i);if(lane==null)return null;
            int mask=lane.optInt("directions",0),active=lane.optInt("active",0);
            if(mask<=0||(mask&~510)!=0||(active&~mask)!=0||Integer.bitCount(active)>1)return null;
            List<String> directions=new ArrayList<>();String recommended="";
            for(int d=0;d<bits.length;d++)if((mask&bits[d])!=0){directions.add(names[d]);if((active&bits[d])!=0)recommended=names[d];}
            if(directions.isEmpty())return null;lanes.add(new RouteIntelligence.Lane(recommended.isEmpty()?directions.get(0):recommended,!recommended.isEmpty(),directions));}
        RouteIntelligence.LaneSection s=new RouteIntelligence.LaneSection(Math.max(0,along-100),along+2,lanes);return s.trusted()?s:null;
    }
    static List<RouteIntelligence.SpeedSection> speedSections(JSONObject trace,List<GeoMath.Point> route)throws Exception{
        List<RouteIntelligence.SpeedSection> result=new ArrayList<>();List<GeoMath.Point> matched=decode(trace.getString("shape"));
        if(matched.size()!=route.size())return result;for(int i=0;i<route.size();i++)if(GeoMath.distance(route.get(i),matched.get(i))>1.5)return result;
        double[] d=offsets(route);JSONArray edges=trace.getJSONArray("edges");
        for(int i=0;i<edges.length();i++){JSONObject e=edges.getJSONObject(i);int speed=e.optInt("speed_limit",0),a=e.optInt("begin_shape_index",-1),b=e.optInt("end_shape_index",-1);
            if(speed>=5&&speed<=140&&a>=0&&b>a&&b<d.length&&d[b]>d[a])result.add(new RouteIntelligence.SpeedSection(d[a],d[b],speed));}
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
