package com.drivemate.app;
import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;
/** Packaged, bounded regional proof. No live GPS or network. */
final class DmUnifiedRegion {
    final DmTileEngine engine;final List<JSONArray> features; final String parentSource;
    DmUnifiedRegion(Context app,boolean stockport)throws Exception{
        String folder=stockport?"dmtiles-stockport/":"dmtiles/";
        byte[] raw=DmTileEngine.read(app.getAssets().open(folder+"manifest.json"),2_000_000);
        String hash=new String(DmTileEngine.read(app.getAssets().open(folder+"manifest.sha256"),128),java.nio.charset.StandardCharsets.UTF_8).trim();
        JSONObject m=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
        if(!"way-node-segments-v1".equals(m.optString("geometry_contract")))throw new IOException("Shared geometry unavailable");
        parentSource=m.optString("parent_source_sha256","");
        engine=new DmTileEngine(raw,hash,n->app.getAssets().open(folder+n+".bin"));
        engine.retainJourney(engine.keys());features=engine.features(engine.keys());
    }
    /** Only enable the regional live renderer if every actual route vertex and ordered way matches the same pinned OSM snapshot. */
    static DmUnifiedGeometry matchLive(Context context, TomTomApi.Route route) {
        if(route.geometrySourceSha.isEmpty() || route.geometryWays.isEmpty() || !RouteMapPolicy.insideStockport(route.points))return null;
        try {
            DmUnifiedRegion region=new DmUnifiedRegion(context,true);
            if(!route.geometrySourceSha.equals(region.parentSource))return null;
            return new DmUnifiedGeometry(region.features,route.points,route.geometryWays);
        }catch(Exception failure){return null;}
    }
    DmTileEngine.Route sample(boolean stockport)throws Exception{
        double[][] choices=stockport?new double[][]{{53.405,-2.165,53.410,-2.171},{53.400,-2.154,53.405,-2.165},{53.410,-2.171,53.400,-2.154}}:new double[][]{{51.209,-2.647,51.207,-2.641}};
        Exception last=null;
        for(double[] s:choices)try{
            DmTileEngine.Route r=engine.route(new GeoMath.Point(s[0],s[1]),new GeoMath.Point(s[2],s[3]));
            if(r.metres>=200&&r.points.size()>=5)return r;
        }catch(Exception e){last=e;}
        throw new IOException("No connected regional sample",last);
    }
}
