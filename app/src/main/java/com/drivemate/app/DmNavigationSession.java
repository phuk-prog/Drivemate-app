package com.drivemate.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import android.util.AtomicFile;

/** Single-threaded experimental navigation state machine. No real-road guidance. */
public final class DmNavigationSession {
    public enum Status { ON_ROUTE, UNCERTAIN, OFF_ROUTE, REROUTED, COVERAGE_MISSING, ARRIVAL_PREVIEW }
    public static final class Update {
        public final Status status;public final double remaining,offRoute;public final DmTileEngine.Route route;
        Update(Status s,double r,double o,DmTileEngine.Route path){status=s;remaining=r;offRoute=o;route=path;}
    }
    private final DmTileEngine engine;private final DmTileDiskSource disk;
    private final AtomicFile state;private final String snapshot;
    private GeoMath.Point destination;private DmTileEngine.Route route;
    private long lastFix=-1,lastAttempt=-60000;private int misses;
    public DmNavigationSession(DmTileEngine engine,DmTileDiskSource disk,File state,String snapshot){
        this.engine=engine;this.disk=disk;this.state=new AtomicFile(state);this.snapshot=snapshot;
    }
    public DmTileEngine.Route plan(GeoMath.Point start,GeoMath.Point end,boolean fetch,double radius,long budget)throws Exception{
        if(!valid(start)||!valid(end))throw new IOException("Invalid journey coordinates");
        DmTileEngine.Route candidate=engine.route(start,end,fetch?null:disk.availableCells());
        if(fetch){DmCellPlanner.Coverage coverage=engine.coverage(candidate,radius,budget);disk.prefetch(coverage.cells);}
        save(end);destination=end;route=candidate;misses=0;lastFix=-1;lastAttempt=-60000;return route;
    }
    private void save(GeoMath.Point end)throws Exception{
        JSONObject o=new JSONObject().put("snapshot",snapshot).put("lat",end.lat).put("lon",end.lon);
        FileOutputStream out=state.startWrite();
        try{out.write(o.toString().getBytes(StandardCharsets.UTF_8));state.finishWrite(out);}
        catch(Exception e){state.failWrite(out);throw e;}
    }
    public DmTileEngine.Route restore(GeoMath.Point current)throws Exception{
        JSONObject o=new JSONObject(new String(DmTileEngine.read(state.openRead(),4096),StandardCharsets.UTF_8));
        if(!snapshot.equals(o.getString("snapshot")))throw new IOException("Journey snapshot changed");
        return plan(current,new GeoMath.Point(o.getDouble("lat"),o.getDouble("lon")),false,0,1);
    }
    private static boolean valid(GeoMath.Point p){return p!=null&&Double.isFinite(p.lat)&&Double.isFinite(p.lon)&&Math.abs(p.lat)<=90&&Math.abs(p.lon)<=180;}
    public Update fix(GeoMath.Point gps,double accuracy,long elapsedMs,boolean fetch)throws Exception{
        if(route==null)throw new IOException("No active preview");
        if(!valid(gps)||!Double.isFinite(accuracy)||accuracy<0||accuracy>35||elapsedMs<0||elapsedMs<=lastFix){
            misses=0;return new Update(Status.UNCERTAIN,Double.NaN,Double.NaN,route);
        }
        lastFix=elapsedMs;GeoMath.Progress progress=GeoMath.project(route.points,gps);
        double remaining=Math.max(0,progress.totalMetres-progress.alongMetres);
        if(progress.offRouteMetres<=Math.max(35,accuracy*2)){
            misses=0;Status status=remaining<25&&GeoMath.distance(gps,destination)<35?Status.ARRIVAL_PREVIEW:Status.ON_ROUTE;
            return new Update(status,remaining,progress.offRouteMetres,route);
        }
        misses++;
        if(misses<3||elapsedMs-lastAttempt<10000)return new Update(Status.OFF_ROUTE,remaining,progress.offRouteMetres,route);
        lastAttempt=elapsedMs;
        try{
            DmTileEngine.Route next=engine.route(gps,destination,fetch?null:disk.availableCells());
            route=next;misses=0;return new Update(Status.REROUTED,GeoMath.length(next.points),progress.offRouteMetres,route);
        }catch(IOException e){return new Update(Status.COVERAGE_MISSING,Double.NaN,progress.offRouteMetres,route);}
    }
    public DmTileEngine.Route current(){return route;}
    public void clear(){state.delete();route=null;destination=null;misses=0;}
}
