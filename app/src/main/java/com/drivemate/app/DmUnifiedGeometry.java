package com.drivemate.app;
import java.io.IOException;
import java.util.*;
import org.json.*;
import org.maplibre.geojson.*;

/** Shared road geometry, verified against every selected route segment. */
final class DmUnifiedGeometry {
    final FeatureCollection roads,areas,route;
    FeatureCollection mappedPoints=FeatureCollection.fromFeatures(new Feature[0]);
    final List<GeoMath.Point> points;
    final int checkedEdges;
    private final Map<Long,Double> routeNodeOffsets=new HashMap<>();
    private final Set<Long> ambiguousNodes=new HashSet<>();
    List<RouteRoadsideAwareness.Event> routeRoadside=Collections.emptyList();
    List<MappedEnforcement.Zone> averageZones=Collections.emptyList();
    private List<Long> orderedRouteWays=Collections.emptyList();
    private double[] orderedOffsets;
    final double[] bounds;
    private final double coordinateScale;
    private Point point(JSONArray c)throws JSONException{
        return Point.fromLngLat(c.getInt(1)/coordinateScale,c.getInt(0)/coordinateScale);
    }
    private static void retainTags(Feature f,JSONObject tags){
        // The authoritative database retains all raw tags. The GPU source needs only display attributes.
        for(String k:new String[]{"name","ref","highway","amenity","shop","tourism","aeroway","railway","public_transport","surface","service","landuse","leisure","natural","building","height","building:levels","min_height","addr:housenumber","entrance","traffic_calming","junction","layer","width","lanes"}){
            if(tags.has(k)){String value=tags.optString(k,"");if(value.length()<=512)f.addStringProperty(k,value);}
        }
        if(tags.has("railway"))f.addStringProperty("kind",tags.optString("railway"));
    }
    private static boolean affirmative(String value){return !value.isEmpty()&&!"no".equals(value)&&!"false".equals(value)&&!"0".equals(value);}
    private static void roadTags(Feature f,JSONObject tags){
        retainTags(f,tags);String one=tags.optString("oneway");
        boolean reverse="-1".equals(one);boolean direct=reverse||"yes".equals(one)||"true".equals(one)||"1".equals(one)||one.isEmpty()&&"roundabout".equals(tags.optString("junction"));
        f.addBooleanProperty("oneway",direct);f.addBooleanProperty("oneway_reverse",reverse);
        f.addBooleanProperty("bridge",affirmative(tags.optString("bridge")));f.addBooleanProperty("tunnel",affirmative(tags.optString("tunnel"))||"yes".equals(tags.optString("covered")));
    }
    private static String areaKind(JSONObject t){if(t.has("landuse"))return t.optString("landuse");if(t.has("leisure"))return t.optString("leisure");if(t.has("amenity"))return t.optString("amenity");return t.optString("natural");}
    private void routeNode(long id, double offset){
        Double previous=routeNodeOffsets.get(id);
        if(previous!=null&&Math.abs(previous-offset)>1)ambiguousNodes.add(id);
        else routeNodeOffsets.put(id,offset);
    }
    void setMappedPoints(JSONArray input)throws Exception{
        if(input.length()>6000)throw new IOException("Too many mapped points");List<Feature> result=new ArrayList<>();
        for(int i=0;i<input.length();i++){JSONArray row=input.getJSONArray(i);double lat=row.getLong(1)/coordinateScale,lon=row.getLong(2)/coordinateScale;if(!covers(new GeoMath.Point(lat,lon)))continue;Feature f=Feature.fromGeometry(Point.fromLngLat(lon,lat));retainTags(f,row.getJSONObject(3));f.addStringProperty("osm_id",Long.toString(row.getLong(0)));result.add(f);}
        mappedPoints=FeatureCollection.fromFeatures(result.toArray(new Feature[0]));
        List<RouteRoadsideAwareness.Event> notices=new ArrayList<>();
        for(int i=0;i<input.length();i++){
            JSONArray row=input.getJSONArray(i);long id=row.getLong(0);Double offset=routeNodeOffsets.get(id);
            if(offset==null||ambiguousNodes.contains(id))continue;
            GeoMath.Point node=new GeoMath.Point(row.getLong(1)/coordinateScale,row.getLong(2)/coordinateScale);
            if(!covers(node)||GeoMath.distance(node,GeoMath.pointAtDistance(points,offset))>.25)continue;
            JSONObject tags=row.getJSONObject(3);
            String direction=tags.optString("traffic_signals:direction",tags.optString("direction",""));
            boolean conditional=false;Iterator<String> keys=tags.keys();while(keys.hasNext()){
                String key=keys.next();if(key.contains(":conditional")||key.contains(":forward")||key.contains(":backward"))conditional=true;
            }
            String label=RouteRoadsideAwareness.label(tags.optString("highway"),tags.optString("traffic_calming"),direction,conditional);
            if(!label.isEmpty())notices.add(new RouteRoadsideAwareness.Event(id,offset,label));
        }
        routeRoadside=Collections.unmodifiableList(notices);
    }
    void setEnforcement(JSONArray relations)throws JSONException{
        if(checkedEdges==0||orderedOffsets==null)return;
        MappedEnforcement.Match matched=MappedEnforcement.match(relations,routeNodeOffsets,ambiguousNodes,orderedRouteWays,orderedOffsets);
        ArrayList<RouteRoadsideAwareness.Event> notices=new ArrayList<>(routeRoadside);
        notices.addAll(matched.notices);
        routeRoadside=Collections.unmodifiableList(notices);
        averageZones=Collections.unmodifiableList(matched.zones);
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
    static DmUnifiedGeometry browse(List<JSONArray> features,double[] bounds)throws Exception{
        return new DmUnifiedGeometry(features,Collections.emptyList(),Collections.emptyList(),null,1e7,bounds);
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
            boolean linear=!kind.isEmpty()&&!"yes".equals(tags.optString("area"))||"ferry".equals(tags.optString("route"))||tags.has("waterway")||tags.has("railway")||tags.has("aerialway")||tags.has("boundary")||Arrays.asList("pier","breakwater","groyne").contains(tags.optString("man_made"));
            if(linear){
                Feature item=Feature.fromGeometry(LineString.fromLngLats(line));
                roadTags(item,tags);item.addStringProperty("osm_id",Long.toString(f.getLong(0)));
                item.addStringProperty("kind",!kind.isEmpty()?kind:tags.has("waterway")?("dam".equals(tags.optString("waterway"))?"dam":"waterway"):tags.has("railway")?"railway":tags.has("aerialway")?"aerialway":tags.has("boundary")?"boundary":tags.has("man_made")?"pier":"ferry");
                item.addStringProperty("name",tags.optString("name",""));item.addStringProperty("ref",tags.optString("ref",""));
                r.add(item);
            }else if(line.size()>=4&&line.get(0).equals(line.get(line.size()-1))){
                String type=tags.has("building")&&!"no".equals(tags.optString("building"))?"building":"water".equals(tags.optString("natural"))?"water":!areaKind(tags).isEmpty()?"land":"";
                if(!type.isEmpty()){
                    Feature item=Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(line)));
                    retainTags(item,tags);item.addStringProperty("kind",type);item.addStringProperty("land_kind",areaKind(tags));
                    if("building".equals(type)){double height=MapDetailPolicy.heightMetres(tags.optString("height"),tags.optString("building:levels"));double min=MapDetailPolicy.explicitMetres(tags.optString("min_height"));item.addNumberProperty("height_m",height);item.addNumberProperty("min_height_m",min>=0&&min<height?min:0);}
                    a.add(item);
                }
            }
        }
        if(routePoints.isEmpty()&&routeWays.isEmpty()&&path==null){
            roads=FeatureCollection.fromFeatures(r.toArray(new Feature[0]));areas=FeatureCollection.fromFeatures(a.toArray(new Feature[0]));
            route=FeatureCollection.fromFeatures(new Feature[0]);points=Collections.emptyList();checkedEdges=0;return;
        }
        if(routePoints.size()<2||routeWays.size()!=routePoints.size()-1)throw new IOException("Route identity mismatch");
        if(path!=null&&(path.points.size()!=path.nodeIds.size()||path.edgeIds.size()!=path.ways.size()))throw new IOException("Route node identity mismatch");
        List<Point> trace=new ArrayList<>();
        double[] routeOffsets=DriveMateProvider.offsets(routePoints);
        orderedRouteWays=new ArrayList<>(routeWays);orderedOffsets=routeOffsets.clone();
        if(path==null){
            // The hosted edge-walk supplied each way ID and the exact route shape.
            // A partial first/last segment is permitted only on the identified original segment.
            for(int i=0;i<routeWays.size();i++){
                JSONArray f=byWay.get(routeWays.get(i));if(f==null||f.getJSONObject(1).optString("highway").isEmpty())throw new IOException("Route way absent from display");
                JSONArray coords=f.getJSONArray(2), refs=f.getJSONArray(3);boolean found=false;
                for(int j=1;j<coords.length();j++){
                    Point x=point(coords.getJSONArray(j-1)),y=point(coords.getJSONArray(j));
                    GeoMath.Point start=new GeoMath.Point(x.latitude(),x.longitude()),end=new GeoMath.Point(y.latitude(),y.longitude());
                    if(RouteMapPolicy.onSameSegment(start,end,routePoints.get(i),routePoints.get(i+1))){
                        // Both geometry and way identity are already checked. Associate
                        // only original endpoint node IDs at actual route vertices.
                        if(exact(x,routePoints.get(i)))routeNode(refs.getLong(j-1),routeOffsets[i]);
                        if(exact(y,routePoints.get(i)))routeNode(refs.getLong(j),routeOffsets[i]);
                        if(exact(x,routePoints.get(i+1)))routeNode(refs.getLong(j-1),routeOffsets[i+1]);
                        if(exact(y,routePoints.get(i+1)))routeNode(refs.getLong(j),routeOffsets[i+1]);
                        found=true;break;
                    }
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
            routeNode(from,routeOffsets[i]);routeNode(to,routeOffsets[i+1]);
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
