package com.drivemate.app;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Original renderer model, no MapLibre/GeoJSON/osmdroid objects. Exact source way/node proof. */
final class NativeMapScene {
    static final int LAND=0,WATER=1,BUILDING=2,PATH=3,LOCAL=4,MAIN=5,MOTORWAY=6,RAIL=7,LABEL=8;
    static final class Shape {
        final long id;final int kind;final double[] xy;final String name;final double w,s,e,n;
        final boolean bridge,tunnel;final boolean area;final double height;final int[] parts;
        Shape(long id,int kind,double[] xy,String name,boolean bridge,boolean tunnel,boolean area){
            this(id,kind,xy,name,bridge,tunnel,area,0,new int[]{0});
        }
        Shape(long id,int kind,double[] xy,String name,boolean bridge,boolean tunnel,boolean area,double height,int[] parts){
            this.id=id;this.kind=kind;this.xy=xy;this.name=name;this.bridge=bridge;this.tunnel=tunnel;this.area=area;this.height=height;this.parts=parts;
            double w=2,s=2,e=-1,n=-1;
            for(int i=0;i<xy.length;i+=2){w=Math.min(w,xy[i]);e=Math.max(e,xy[i]);s=Math.min(s,xy[i+1]);n=Math.max(n,xy[i+1]);}
            this.w=w;this.s=s;this.e=e;this.n=n;
        }
    }
    final List<Shape> shapes;final List<GeoMath.Point> route;final int checkedEdges;final double[] bounds;
    final List<RouteRoadsideAwareness.Event> routeRoadside;
    final List<MappedEnforcement.Zone> averageZones;
    private NativeMapScene(List<Shape> shapes,List<GeoMath.Point> route,int edges,double[] bounds){
        this.shapes=Collections.unmodifiableList(shapes);this.route=Collections.unmodifiableList(new ArrayList<>(route));checkedEdges=edges;this.bounds=bounds.clone();
        routeRoadside=Collections.emptyList();averageZones=Collections.emptyList(); // no exact node-tag/relationship evidence in current regional pack
    }
    private static boolean yes(String tag){return !tag.isEmpty()&&!Arrays.asList("no","false","0").contains(tag);}
    static int kind(JSONObject t,boolean closed){
        String h=t.optString("highway");
        if(!h.isEmpty()&&!"yes".equals(t.optString("area"))){
            if(h.startsWith("motorway")||h.startsWith("trunk"))return MOTORWAY;
            if(h.startsWith("primary")||h.startsWith("secondary")||h.startsWith("tertiary"))return MAIN;
            if(Arrays.asList("footway","cycleway","path","steps","bridleway","track","pedestrian").contains(h))return PATH;
            return LOCAL;
        }
        if(t.has("railway"))return RAIL;
        if(t.has("waterway"))return WATER;
        if(!closed)return -1;
        if(yes(t.optString("building")))return BUILDING;
        if("water".equals(t.optString("natural"))||t.has("water"))return WATER;
        if(t.has("landuse")||t.has("leisure")||t.has("natural"))return LAND;
        return -1;
    }
    static NativeMapScene fromRegional(List<JSONArray> rows,DmTileEngine.Route path,double[] bounds)throws Exception{
        return create(rows,path.points,path.ways,path.nodeIds,bounds);
    }
    static NativeMapScene create(List<JSONArray> rows,List<GeoMath.Point> points,List<Long> ways,List<Long> nodes,double[] bounds)throws Exception{
        if((!points.isEmpty()&&(points.size()<2||ways.size()!=points.size()-1||nodes.size()!=points.size()))||(points.isEmpty()&&(!ways.isEmpty()||!nodes.isEmpty()))||bounds==null||bounds.length!=4||rows.size()>16000)throw new IOException("Invalid native scene identity");
        Map<Long,JSONArray> exact=new HashMap<>();List<Shape> shapes=new ArrayList<>();int vertices=0;
        for(JSONArray row:rows){
            if(row.length()!=4||exact.put(row.getLong(0),row)!=null)throw new IOException("Duplicate or invalid map way");
            JSONArray coords=row.getJSONArray(2),refs=row.getJSONArray(3);if(coords.length()!=refs.length())throw new IOException("Missing map node identities");
            vertices+=coords.length();if(vertices>160000)throw new IOException("Native map vertex budget exceeded");
            if(coords.length()<2)continue;
            boolean closed=coords.length()>=4&&refs.getLong(0)==refs.getLong(refs.length()-1);
            JSONObject tags=row.getJSONObject(1);int kind=kind(tags,closed);if(kind<0)continue;
            double[] xy=new double[coords.length()*2];
            for(int i=0;i<coords.length();i++){
                JSONArray p=coords.getJSONArray(i);double lat=p.getLong(0)/1e6,lon=p.getLong(1)/1e6;
                if(Math.abs(lat)>NativeMapProjection.MAX_LAT||Math.abs(lon)>180)throw new IOException("Invalid source coordinate");
                xy[2*i]=NativeMapProjection.x(lon);xy[2*i+1]=NativeMapProjection.y(lat);
            }
            String name=tags.optString("name",tags.optString("ref"));if(name.length()>120)name="";
            shapes.add(new Shape(row.getLong(0),kind,xy,name,yes(tags.optString("bridge")),yes(tags.optString("tunnel")),closed&&(kind==LAND||kind==WATER||kind==BUILDING)&&!tags.has("waterway"),kind==BUILDING?MapDetailPolicy.heightMetres(tags.optString("height"),tags.optString("building:levels")):0,new int[]{0}));
        }
        for(int i=0;i<ways.size();i++){
            JSONArray row=exact.get(ways.get(i));if(row==null||row.getJSONObject(1).optString("highway").isEmpty())throw new IOException("Route road missing");
            JSONArray refs=row.getJSONArray(3),coords=row.getJSONArray(2);boolean found=false;
            for(int j=1;j<refs.length();j++){
                long a=refs.getLong(j-1),b=refs.getLong(j);
                boolean forward=a==nodes.get(i)&&b==nodes.get(i+1),reverse=b==nodes.get(i)&&a==nodes.get(i+1);
                if(!forward&&!reverse)continue;
                if(matches(coords.getJSONArray(forward?j-1:j),points.get(i))&&matches(coords.getJSONArray(forward?j:j-1),points.get(i+1))){found=true;break;}
            }
            if(!found)throw new IOException("Native renderer route differs from original way");
        }
        shapes.sort(Comparator.comparingInt((Shape s)->s.bridge?100+s.kind:s.tunnel?3:s.kind));
        return new NativeMapScene(shapes,points,ways.size(),bounds);
    }
    static NativeMapScene browse(List<JSONArray> rows,double[] bounds)throws Exception{
        return create(rows,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),bounds);
    }
    static NativeMapScene tiles(List<Shape> shapes,double[] bounds){return new NativeMapScene(new ArrayList<>(shapes),Collections.emptyList(),0,bounds);}
    boolean covers(GeoMath.Point p){return RouteMapPolicy.insideBounds(p,bounds);}
    private static boolean matches(JSONArray p,GeoMath.Point q)throws JSONException{
        return p.getLong(0)==Math.round(q.lat*1e6)&&p.getLong(1)==Math.round(q.lon*1e6);
    }
}
