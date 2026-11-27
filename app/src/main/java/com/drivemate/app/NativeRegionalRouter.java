package com.drivemate.app;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** DriveMate-only on-device candidate. No provider HTTP calls. The production start guard remains. */
final class NativeRegionalRouter {
    static final String PROVIDER="DriveMate local preview";
    private final Context app;
    NativeRegionalRouter(Context context){app=context.getApplicationContext();}
    /** Counters for the diagnostics report: searches, failures, snap distances, alternatives. */
    static final RoutingStats STATS=new RoutingStats();
    TomTomApi.Route preview(GeoMath.Point from,GeoMath.Point to)throws Exception{return preview(from,to,Double.NaN);}
    /** @param travelBearing car heading so the route starts at a junction ahead of the car, or NaN when unknown */
    TomTomApi.Route preview(GeoMath.Point from,GeoMath.Point to,double travelBearing)throws Exception{
        STATS.recordRequest();
        try{return plan(from,to,travelBearing,null);}
        catch(Exception failure){
            String m=String.valueOf(failure.getMessage());
            STATS.recordFailure(failure instanceof DmTileEngine.CoverageException?"coverage":m.startsWith("No connected route")?"no-route":failure instanceof IOException?"io":"error");
            throw failure;
        }
    }
    /** A second route over different roads, or an IOException when none is distinct and fast enough. */
    TomTomApi.Route alternative(GeoMath.Point from,GeoMath.Point to,double travelBearing,TomTomApi.Route primary)throws Exception{
        if(primary==null||primary.geometryWays.isEmpty())throw new IOException("No primary route to differ from");
        TomTomApi.Route alt;
        try{alt=plan(from,to,travelBearing,new HashSet<>(primary.geometryWays));}
        catch(IOException none){STATS.recordAlternative(false);throw none;}
        boolean ok=AlternativeRoutes.accept(primary.points,primary.timeSeconds,alt.points,alt.timeSeconds);
        STATS.recordAlternative(ok);
        if(!ok)throw new IOException("No distinct alternative route");
        return alt;
    }
    private TomTomApi.Route plan(GeoMath.Point from,GeoMath.Point to,double travelBearing,Set<Long> penalisedWays)throws Exception{
        List<DmUnifiedRegion> candidates=new NativeRegionPacks(app).candidates(from,to);
        Exception last=null;
        for(DmUnifiedRegion region:candidates){
            try{
                DmTileEngine.Route path=region.engine.route(from,to,null,travelBearing,penalisedWays);
                for(GeoMath.Point p:path.points)if(!region.covers(p))throw new DmTileEngine.CoverageException("Route crosses unverified regional boundary");
                STATS.recordSuccess(path.expansions,path.ms);
                return convert(region,path,from,to);
            }catch(DmTileEngine.CoverageException noPath){last=noPath;}
        }
        throw new IOException("No connected route within verified installed regional coverage. No external fallback.",last);
    }
    /** Explicit reroute preview, reconstructed using the same verified graph and destination.
     * Never returns an old path as a successful replacement on missing coverage. */
    TomTomApi.Route reroutePreview(GeoMath.Point from,GeoMath.Point destination)throws Exception{return preview(from,destination);}
    static TomTomApi.Route convert(DmUnifiedRegion region,DmTileEngine.Route path,GeoMath.Point from,GeoMath.Point to)throws Exception{
        if(path.points.size()>=2)STATS.recordSnap(GeoMath.distance(from,path.points.get(0)),GeoMath.distance(to,path.points.get(path.points.size()-1)));
        // The engine snaps to junctions up to 350 m away, so allow the route to start up to 250 m from
        // the GPS fix (the join-the-route logic handles that) and end up to 350 m from the destination
        // (the last instruction says "check destination access").
        if(path.points.size()<2||GeoMath.distance(from,path.points.get(0))>250
                ||GeoMath.distance(to,path.points.get(path.points.size()-1))>350)
            throw new IOException("Route endpoints too far from verified road nodes");
        Map<Long,JSONObject> tags=region.engine.routeTags(path);
        int[] choices=region.engine.legalChoices(path);
        // Exit counts and forks need extra map lookups. If they fail the route still gets plain wording.
        JunctionDetail[] details=null;
        try{details=region.engine.junctionDetails(path);}catch(Exception noDetail){details=null;}
        List<TomTomApi.Instruction> instructions=NativeManeuvers.steps(path.points,path.ways,tags,choices,details);
        List<RouteIntelligence.LaneSection> lanes=NativeManeuvers.lanes(path.points,path.ways,tags,instructions);
        List<RouteIntelligence.SpeedSection> limits=NativeManeuvers.limits(path.points,path.ways,tags);
        TomTomApi.Route route=new TomTomApi.Route(path.points,instructions,GeoMath.length(path.points),
                Math.max(1,(int)Math.ceil(path.seconds)),0,lanes,limits,Collections.emptyList(),PROVIDER);
        route.geometrySourceSha=region.parentSource;route.geometryWays=path.ways;
        route.nativeScene=region.nativeScene(path);
        return route;
    }
    static final class RoadContext {
        final List<JSONArray> ways;final double[] bounds;final NativeMapScene map;final long created=System.currentTimeMillis();
        RoadContext(List<JSONArray> ways,double[] bounds,NativeMapScene map){this.ways=ways;this.bounds=bounds;this.map=map;}
        boolean freshAt(GeoMath.Point p){
            long age=System.currentTimeMillis()-created;
            return age>=0&&age<=120000&&p!=null&&p.lon>bounds[0]+.0007&&p.lon<bounds[2]-.0007&&p.lat>bounds[1]+.0005&&p.lat<bounds[3]-.0005;
        }
    }
    RoadContext roadContext(GeoMath.Point p)throws Exception{
        DmUnifiedRegion region=new NativeRegionPacks(app).select(p,null);
        double[] regionBounds=region.regionBounds;
        double[] bounds=DmUnifiedRegion.localBounds(p,regionBounds);
        List<JSONArray> features=region.engine.boundedFeatures(region.engine.keys(),bounds,Collections.emptySet(),false,6000,100000);
        NativeMapScene geometry=NativeMapScene.browse(features,bounds);
        List<JSONArray> ways=new ArrayList<>();
        // Existing conservative matcher uses E7 coordinates. Convert exactly from E6,
        // retaining original way and node IDs; no coordinate interpolation.
        for(JSONArray row:features){
            JSONObject tags=row.getJSONObject(1);if(!tags.has("highway"))continue;
            JSONArray coords=row.getJSONArray(2),e7=new JSONArray();
            for(int i=0;i<coords.length();i++){JSONArray c=coords.getJSONArray(i);e7.put(new JSONArray().put(c.getLong(0)*10).put(c.getLong(1)*10));}
            ways.add(new JSONArray().put(row.getLong(0)).put(tags).put(e7).put(row.getJSONArray(3)));
        }
        return new RoadContext(Collections.unmodifiableList(ways),bounds,geometry);
    }
    List<TomTomApi.Destination> search(String query,GeoMath.Point near)throws Exception{
        String q=query==null?"":query.trim();if(q.length()<2||q.length()>100)return Collections.emptyList();
        NativeRegionPacks packs=new NativeRegionPacks(app);
        DmUnifiedRegion region;
        try{region=near==null?new DmUnifiedRegion(app,true):packs.select(near,null);}catch(IOException noRegion){return Collections.emptyList();}
        List<JSONArray> rows=region.engine.regionalSearch(q);
        List<TomTomApi.Destination> out=new ArrayList<>();
        for(JSONArray row:rows){
            GeoMath.Point p=new GeoMath.Point(row.getInt(1)/1e6,row.getInt(2)/1e6);
            if(!region.covers(p))continue;
            out.add(new TomTomApi.Destination(row.getString(0),row.optString(3,"Mapped place"),p));
        }
        // A postcode or mapped address is a location, never an inferred driveway/entrance.
        if(near!=null)out.sort(Comparator.comparingDouble(d->GeoMath.distance(near,d.point)));
        return new ArrayList<>(out.subList(0,Math.min(20,out.size())));
    }
}
