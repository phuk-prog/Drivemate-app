package com.drivemate.app;

import android.content.Context;
import java.util.*;
import java.io.IOException;
import org.json.*;
import org.maplibre.geojson.*;

/** Reads only bounded local cells from the checksum-verified regional pack.
 * Buildings are display polygons only; never infer a destination or a road.
 */
final class MappedRegionalBuildings {
    private MappedRegionalBuildings(){}
    private static final String FOLDER="dmtiles-stockport/";
    private static final String SOURCE="2c31f7d0c5c7280aee2130f38f7ebabb990626a8f5a208dba3c0279ada6c3164";
    static FeatureCollection load(Context app,GeoMath.Point center)throws Exception{
        if(!RouteMapPolicy.insideStockport(center))return empty();
        byte[] manifest=DmTileEngine.read(app.getAssets().open(FOLDER+"manifest.json"),2_000_000);
        String sha=new String(DmTileEngine.read(app.getAssets().open(FOLDER+"manifest.sha256"),128),java.nio.charset.StandardCharsets.UTF_8).trim();
        JSONObject metadata=new JSONObject(new String(manifest,java.nio.charset.StandardCharsets.UTF_8));
        if(!SOURCE.equals(metadata.optString("parent_source_sha256"))||!"way-node-segments-v1".equals(metadata.optString("geometry_contract")))throw new IOException("Unverified regional building source");
        DmTileEngine engine=new DmTileEngine(manifest,sha,n->app.getAssets().open(FOLDER+n+".bin"));
        double[] bound=RouteMapPolicy.stockportBounds();
        double west=Math.max(bound[0],center.lon-.010),east=Math.min(bound[2],center.lon+.010),south=Math.max(bound[1],center.lat-.006),north=Math.min(bound[3],center.lat+.006);
        Set<String> keys=new TreeSet<>();
        for(double y:new double[]{south,center.lat,north})for(double x:new double[]{west,center.lon,east})keys.add(DmTileEngine.key(y,x));
        keys.retainAll(engine.keys());
        List<Feature> buildings=new ArrayList<>();int vertices=0;
        for(JSONArray way:engine.features(keys)){
            if(buildings.size()>=4000||vertices>=60000)break;
            JSONObject tags=way.getJSONObject(1);
            if(!tags.has("building")||"no".equals(tags.optString("building")))continue;
            JSONArray coords=way.getJSONArray(2);
            if(coords.length()<4||coords.length()>2000||vertices+coords.length()>60000)continue;
            List<Point> polygon=new ArrayList<>();double minX=180,maxX=-180,minY=90,maxY=-90;
            for(int i=0;i<coords.length();i++){
                JSONArray pair=coords.getJSONArray(i);double y=pair.getInt(0)/1e6,x=pair.getInt(1)/1e6;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                polygon.add(Point.fromLngLat(x,y));
            }
            if(maxX<west||minX>east||maxY<south||minY>north||!polygon.get(0).equals(polygon.get(polygon.size()-1)))continue;
            Feature f=Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(polygon)));
            f.addStringProperty("kind","building");buildings.add(f);vertices+=polygon.size();
        }
        return FeatureCollection.fromFeatures(buildings.toArray(new Feature[0]));
    }
    static FeatureCollection onlyBuildings(FeatureCollection areas){
        List<Feature> selected=new ArrayList<>();
        if(areas!=null&&areas.features()!=null)for(Feature f:areas.features()){
            if(selected.size()>=4000)break;
            if(f!=null&&f.geometry() instanceof Polygon&&f.hasProperty("kind")&&"building".equals(f.getStringProperty("kind")))selected.add(f);
        }
        return FeatureCollection.fromFeatures(selected.toArray(new Feature[0]));
    }
    private static FeatureCollection empty(){return FeatureCollection.fromFeatures(new Feature[0]);}
}
