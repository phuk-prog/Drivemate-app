package com.drivemate.app;
import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;
/** Packaged, bounded regional proof. No live GPS or network. */
final class DmUnifiedRegion {
    final DmTileEngine engine;final String parentSource;private final boolean stockport;
    DmUnifiedRegion(Context app,boolean stockport)throws Exception{
        this.stockport=stockport;
        String folder=stockport?"dmtiles-stockport/":"dmtiles/";
        byte[] raw=DmTileEngine.read(app.getAssets().open(folder+"manifest.json"),2_000_000);
        String hash=new String(DmTileEngine.read(app.getAssets().open(folder+"manifest.sha256"),128),java.nio.charset.StandardCharsets.UTF_8).trim();
        JSONObject m=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
        if(!"way-node-segments-v1".equals(m.optString("geometry_contract")))throw new IOException("Shared geometry unavailable");
        parentSource=m.optString("parent_source_sha256","");
        engine=new DmTileEngine(raw,hash,n->app.getAssets().open(folder+n+".bin"));
        // Route search loads its own bounded cells; do not pin the entire regional pack.
    }
    static double[] displayBounds(List<GeoMath.Point> points,boolean stockport)throws IOException{
        if(points==null||points.size()<2)throw new IOException("No regional route");
        double w=180,s=90,e=-180,n=-90;
        for(GeoMath.Point p:points){
            if(!Double.isFinite(p.lat)||!Double.isFinite(p.lon))throw new IOException("Invalid route");
            w=Math.min(w,p.lon);e=Math.max(e,p.lon);s=Math.min(s,p.lat);n=Math.max(n,p.lat);
        }
        w-=.012;s-=.008;e+=.012;n+=.008;
        if(stockport){double[] b=RouteMapPolicy.stockportBounds();w=Math.max(w,b[0]);s=Math.max(s,b[1]);e=Math.min(e,b[2]);n=Math.min(n,b[3]);}
        if(e<=w||n<=s||e-w>.055||n-s>.035)throw new IOException("Route exceeds bounded regional display");
        return new double[]{w,s,e,n};
    }
    private List<JSONArray> context(List<GeoMath.Point> points,List<Long> ways,double[] bounds)throws Exception{
        return engine.boundedFeatures(engine.keys(),bounds,new HashSet<>(ways),false,16000,160000);
    }
    DmUnifiedGeometry geometry(DmTileEngine.Route route)throws Exception{
        double[] bounds=displayBounds(route.points,stockport);
        return DmUnifiedGeometry.regional(context(route.points,route.ways,bounds),route,bounds);
    }
    DmUnifiedGeometry geometry(List<GeoMath.Point> points,List<Long> ways)throws Exception{
        double[] bounds=displayBounds(points,stockport);
        return DmUnifiedGeometry.regional(context(points,ways,bounds),points,ways,bounds);
    }
    /** Only enable the regional live renderer if every actual route vertex and ordered way matches the same pinned OSM snapshot. */
    static DmUnifiedGeometry matchLive(Context context, TomTomApi.Route route) {
        if(route.geometrySourceSha.isEmpty() || route.geometryWays.isEmpty() || !RouteMapPolicy.insideStockport(route.points))return null;
        try {
            DmUnifiedRegion region=new DmUnifiedRegion(context,true);
            if(!route.geometrySourceSha.equals(region.parentSource))return null;
            return region.geometry(route.points,route.geometryWays);
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
