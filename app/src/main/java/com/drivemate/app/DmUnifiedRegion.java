package com.drivemate.app;
import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;
/** Packaged, bounded regional proof. No live GPS or network. */
final class DmUnifiedRegion {
    final DmTileEngine engine;final String parentSource;private final boolean stockport;final double[] regionBounds;
    DmUnifiedRegion(Context app,boolean stockport)throws Exception{
        this.stockport=stockport;regionBounds=stockport?RouteMapPolicy.stockportBounds():null;
        String folder=stockport?"dmtiles-stockport/":"dmtiles/";
        byte[] raw=DmTileEngine.read(app.getAssets().open(folder+"manifest.json"),2_000_000);
        String hash=new String(DmTileEngine.read(app.getAssets().open(folder+"manifest.sha256"),128),java.nio.charset.StandardCharsets.UTF_8).trim();
        JSONObject m=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
        if(!"way-node-segments-v1".equals(m.optString("geometry_contract")))throw new IOException("Shared geometry unavailable");
        parentSource=m.optString("parent_source_sha256","");
        engine=new DmTileEngine(raw,hash,n->app.getAssets().open(folder+n+".bin"));
        // Route search loads its own bounded cells; do not pin the entire regional pack.
    }
    DmUnifiedRegion(byte[] manifest,String sha,DmTileEngine.Source source,double[] bounds)throws Exception{
        stockport=false;regionBounds=bounds.clone();JSONObject m=new JSONObject(new String(manifest,java.nio.charset.StandardCharsets.UTF_8));
        parentSource=m.getString("parent_source_sha256");engine=new DmTileEngine(manifest,sha,source);
    }
    boolean covers(GeoMath.Point p){return regionBounds!=null&&RouteMapPolicy.insideBounds(p,regionBounds);}
    static double[] displayBounds(List<GeoMath.Point> points,boolean stockport)throws IOException{
        return displayBounds(points,stockport,1);
    }
    static double[] displayBounds(List<GeoMath.Point> points,boolean stockport,double margin)throws IOException{
        if(points==null||points.size()<2||!Double.isFinite(margin)||margin<=0||margin>1)throw new IOException("No regional route or invalid margin");
        double w=180,s=90,e=-180,n=-90;
        for(GeoMath.Point p:points){
            if(!Double.isFinite(p.lat)||!Double.isFinite(p.lon))throw new IOException("Invalid route");
            w=Math.min(w,p.lon);e=Math.max(e,p.lon);s=Math.min(s,p.lat);n=Math.max(n,p.lat);
        }
        w-=.012*margin;s-=.008*margin;e+=.012*margin;n+=.008*margin;
        if(stockport){double[] b=RouteMapPolicy.stockportBounds();w=Math.max(w,b[0]);s=Math.max(s,b[1]);e=Math.min(e,b[2]);n=Math.min(n,b[3]);}
        if(e<=w||n<=s||e-w>.055||n-s>.035)throw new IOException("Route exceeds bounded regional display");
        return new double[]{w,s,e,n};
    }
    private static final double[] MARGINS={1,.65,.4,.2};
    private static final class DisplayContext {
        final double[] bounds;final List<JSONArray> features;
        DisplayContext(double[] b,List<JSONArray> f){bounds=b;features=f;}
    }
    private DisplayContext context(List<GeoMath.Point> points,List<Long> ways)throws Exception{
        Set<Long> required=new HashSet<>(ways);
        Exception last=null;
        // A dense neighbourhood must not make the complete preview unavailable.
        // Reduce only optional context, never the route or its original way IDs.
        for(double margin:MARGINS){
            double[] bounds;
            try{bounds=displayBounds(points,stockport,margin);
                if(regionBounds!=null){bounds[0]=Math.max(bounds[0],regionBounds[0]);bounds[1]=Math.max(bounds[1],regionBounds[1]);bounds[2]=Math.min(bounds[2],regionBounds[2]);bounds[3]=Math.min(bounds[3],regionBounds[3]);if(bounds[2]<=bounds[0]||bounds[3]<=bounds[1])throw new IOException("Outside regional context");}
            }catch(IOException tooWide){last=tooWide;continue;}
            try{
                List<JSONArray> rows=engine.boundedFeatures(engine.keys(),bounds,required,false,16000,160000);
                android.util.Log.i("UnifiedRegion","Bounded regional context: "+rows.size()+" features, margin "+margin);
                return new DisplayContext(bounds,rows);
            }catch(DmTileEngine.CoverageException dense){
                last=dense;
                android.util.Log.w("UnifiedRegion","Regional context unavailable at margin "+margin+": "+dense.getMessage());
            }
        }
        throw new IOException("No verified regional context fits display bounds",last);
    }
    /** Raw source geometry for the original DriveMate rendering library. */
    NativeMapScene nativeScene(DmTileEngine.Route route)throws Exception{
        DisplayContext c=context(route.points,route.ways);
        return NativeMapScene.fromRegional(c.features,route,c.bounds).withIntelligence(engine.routeIntelligence(route));
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
