package com.drivemate.app;
import java.io.IOException;
import java.util.*;
import org.json.*;
import org.maplibre.geojson.*;

/** Shared road geometry, verified against every selected route segment. */
final class DmUnifiedGeometry {
    final FeatureCollection roads,areas,route;
    final List<GeoMath.Point> points;
    final int checkedEdges;
    final double[] bounds;
    private final double coordinateScale;
    private Point point(JSONArray c)throws JSONException{
        return Point.fromLngLat(c.getInt(1)/coordinateScale,c.getInt(0)/coordinateScale);
    }
    boolean covers(GeoMath.Point p){return RouteMapPolicy.insideBounds(p,bounds);}
    private static boolean exact(Point p,GeoMath.Point g){
        return Math.round(p.latitude()*1e6)==Math.round(g.lat*1e6)
            &&Math.round(p.longitude()*1e6)==Math.round(g.lon*1e6);
    }
    DmUnifiedGeometry(List<JSONArray> features,DmTileEngine.Route path)throws Exception{
        this(features,path.points,path.ways,path,1e6,new double[]{-2.188,53.392,-2.137,53.423});
    }
    DmUnifiedGeometry(List<JSONArray> features,List<GeoMath.Point> points,List<Long> ways)throws Exception{
        this(features,points,ways,null,1e6,new double[]{-2.188,53.392,-2.137,53.423});
    }
    DmUnifiedGeometry(List<JSONArray> features,List<GeoMath.Point> points,List<Long> ways,double[] bounds)throws Exception{
        this(features,points,ways,null,1e7,bounds);
    }
    private DmUnifiedGeometry(List<JSONArray> features,List<GeoMath.Point> routePoints,List<Long> routeWays,DmTileEngine.Route path,double scale,double[] bounds)throws Exception{
        this.coordinateScale=scale;this.bounds=bounds.clone();
        Map<Long,JSONArray> byWay=new HashMap<>();List<Feature> r=new ArrayList<>(),a=new ArrayList<>();
        for(JSONArray f:features){
            if(f.length()!=4||f.getJSONArray(2).length()!=f.getJSONArray(3).length())throw new IOException("Missing shared way identities");
            if(byWay.put(f.getLong(0),f)!=null)throw new IOException("Duplicate way");
            JSONObject tags=f.getJSONObject(1);JSONArray coords=f.getJSONArray(2);List<Point> line=new ArrayList<>();
            for(int i=0;i<coords.length();i++)line.add(point(coords.getJSONArray(i)));
            if(line.size()<2)continue;
            String kind=tags.optString("highway","");
            if((!kind.isEmpty()&&!"yes".equals(tags.optString("area")))||"ferry".equals(tags.optString("route"))||tags.has("waterway")){
                Feature item=Feature.fromGeometry(LineString.fromLngLats(line));
                item.addStringProperty("kind",!kind.isEmpty()?kind:tags.has("waterway")?"waterway":"ferry");
                item.addStringProperty("name",tags.optString("name",""));item.addStringProperty("ref",tags.optString("ref",""));
                r.add(item);
            }else if(line.size()>=4&&line.get(0).equals(line.get(line.size()-1))){
                String type=tags.has("building")?"building":"water".equals(tags.optString("natural"))?"water":tags.has("landuse")||tags.has("leisure")?"land":"";
                if(!type.isEmpty()){
                    Feature item=Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(line)));
                    item.addStringProperty("kind",type);a.add(item);
                }
            }
        }
        if(routePoints.size()<2||routeWays.size()!=routePoints.size()-1)throw new IOException("Route identity mismatch");
        if(path!=null&&(path.points.size()!=path.nodeIds.size()||path.edgeIds.size()!=path.ways.size()))throw new IOException("Route node identity mismatch");
        List<Point> trace=new ArrayList<>();
        if(path==null){
            // The hosted edge-walk supplied each way ID and the exact route shape.
            // A partial first/last segment is permitted only on the identified original segment.
            for(int i=0;i<routeWays.size();i++){
                JSONArray f=byWay.get(routeWays.get(i));if(f==null||f.getJSONObject(1).optString("highway").isEmpty())throw new IOException("Route way absent from display");
                JSONArray coords=f.getJSONArray(2);boolean found=false;
                for(int j=1;j<coords.length();j++){
                    Point x=point(coords.getJSONArray(j-1)),y=point(coords.getJSONArray(j));
                    GeoMath.Point start=new GeoMath.Point(x.latitude(),x.longitude()),end=new GeoMath.Point(y.latitude(),y.longitude());
                    if(RouteMapPolicy.onSameSegment(start,end,routePoints.get(i),routePoints.get(i+1))){found=true;break;}
                }
                if(!found)throw new IOException("Hosted route segment differs from identified road");
            }
            for(GeoMath.Point q:routePoints)trace.add(Point.fromLngLat(q.lon,q.lat));
        }else for(int i=0;i<path.ways.size();i++){
            JSONArray f=byWay.get(path.ways.get(i));if(f==null)throw new IOException("Route way absent from display");
            JSONArray refs=f.getJSONArray(3),coords=f.getJSONArray(2);
            long from=path.nodeIds.get(i),to=path.nodeIds.get(i+1);Point first=null,last=null;
            for(int j=1;j<refs.length();j++){
                long x=refs.getLong(j-1),y=refs.getLong(j);
                if(x==from&&y==to){first=point(coords.getJSONArray(j-1));last=point(coords.getJSONArray(j));break;}
                if(y==from&&x==to){first=point(coords.getJSONArray(j));last=point(coords.getJSONArray(j-1));break;}
            }
            if(first==null||!exact(first,path.points.get(i))||!exact(last,path.points.get(i+1)))throw new IOException("Route edge differs from displayed road");
            if(trace.isEmpty())trace.add(first);
            else if(!trace.get(trace.size()-1).equals(first))throw new IOException("Disconnected route");
            trace.add(last);
        }
        if(trace.size()<2)throw new IOException("No shared route");
        roads=FeatureCollection.fromFeatures(r.toArray(new Feature[0]));areas=FeatureCollection.fromFeatures(a.toArray(new Feature[0]));
        route=FeatureCollection.fromFeatures(new Feature[]{Feature.fromGeometry(LineString.fromLngLats(trace))});
        List<GeoMath.Point> exact=new ArrayList<>();for(Point q:trace)exact.add(new GeoMath.Point(q.latitude(),q.longitude()));
        points=Collections.unmodifiableList(exact);checkedEdges=routeWays.size();
    }
}
