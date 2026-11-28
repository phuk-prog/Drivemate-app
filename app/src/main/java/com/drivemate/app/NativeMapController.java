package com.drivemate.app;
import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.util.*;
import java.util.concurrent.*;

/** Main map controller: native rendering and regional data, no external map SDK. */
final class NativeMapController implements DriveMap {
    private final Activity activity;private final NativeMapView view;private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private NativeMapScene current;
    private final NativeTileSource tileSource;
    private NativeMapScene verifiedRoute;
    private MappedRoadside roadside;
    private MappedSignals signals;

    private int viewportTop,viewportBottom;
    private List<GeoMath.Point> junctionContext=Collections.emptyList();
    private List<GeoMath.Point> route=Collections.emptyList();
    private GeoMath.Point destination,entrance,parked,center;
    private boolean night,traffic,disposed,busy;
    private int generation;
    private long requestedAt;

    private RoadDisplayHealth.State reported;
    /** At least one detail load has completed (successfully or not). */
    private volatile boolean loadFinished;
    private int detailRetries;
    /** Base map drawn underneath the verified route scene, so the screen is never blank around the route. */
    private NativeMapScene background;
    private GeoMath.Point backgroundAt;private double backgroundZoom=-1;
    /** Why the last detail load failed, or empty. Shown in diagnostics. */
    private volatile String lastDetailError="";
    /** DriveMate GPU engine (beta): OpenGL draws areas and roads under the classic view, which keeps names,
     *  route, markers, car and gestures. Null for the classic engine. */
    private final GlBaseView gl;
    private final View root;
    NativeMapController(Activity activity,Listener listener){this(activity,listener,false);}
    NativeMapController(Activity activity,Listener listener,boolean gpu){
        this.activity=activity;this.listener=listener;view=new NativeMapView(activity);tileSource=new NativeTileSource(activity);
        if(gpu){
            gl=new GlBaseView(activity);
            android.widget.FrameLayout box=new android.widget.FrameLayout(activity);
            box.addView(gl,new android.widget.FrameLayout.LayoutParams(-1,-1));
            box.addView(view,new android.widget.FrameLayout.LayoutParams(-1,-1));
            view.setGpuBase(gl);root=box;
            gl.setOnFailure(()->view.setGpuBase(null));
        }else{gl=null;root=view;}
        view.setCameraChanged(()->prefetch(view.center()));
        view.setPaintListener(()->{
            // FAILED only after a detail load has finished with nothing drawn; never on the first frame.
            MapLoadHealth.Report report=MapLoadHealth.report(loadFinished,busy,view.roadsDrawn());
            if(report==MapLoadHealth.Report.NONE)return;
            RoadDisplayHealth.State state=report==MapLoadHealth.Report.READY?RoadDisplayHealth.State.READY:RoadDisplayHealth.State.FAILED;
            if(state!=reported){reported=state;view.post(()->{if(!disposed)listener.status(state,view.roadsDrawn());});}
        });
    }
    public View view(){return root;}
    public double[] limitMarkerOffsets(){return view.limitMarkerOffsets();}
    public String engineName(){return gl!=null?"DriveMate (GPU)":"DriveMate classic";}
    public void setOnUserGesture(Runnable r){view.setGesture(r);}
    /** Pre-fetch map tiles around a place (Home, Work) in the background. */
    public void warmArea(GeoMath.Point p){if(!disposed&&p!=null)worker.execute(()->tileSource.warm(p));}
    public boolean nightMode(){return night;}
    public boolean hasRenderedRoads(){return view.roadsDrawn()>0;}
    public double currentZoom(){return view.zoom();}
    public void setVisible(boolean v){root.setVisibility(v?View.VISIBLE:View.INVISIBLE);}
    String debugStats(){return (gl!=null?gl.stats()+" · ":"")+tileSource.lastStats()+" · overlay "+(current==null?"none":current==verifiedRoute?"route":"box")+" · zoom "+String.format(java.util.Locale.UK,"%.1f",view.zoom());}
    public void setDebug(boolean on){view.setDebug(on?this::debugStats:null);}
        public String geometryDescription(){return current==null?"DriveMate native · regional detail unavailable"+(lastDetailError.isEmpty()?"":" ("+lastDetailError+")"):current.checkedEdges>0?"DriveMate native · verified route roads":"DriveMate native · regional roads";}
    public String routeAlignmentDiagnostic(){return current!=null&&current.checkedEdges>0?current.checkedEdges+" exact source edges":"unverified";}
    public void setHighDetailEnabled(boolean enabled){}
    public void setBuildings3D(boolean enabled){view.setBuildings(enabled);}
    public void setNavigationDetail(boolean navigating){}
    public void setNightMode(boolean v){night=v;view.setNight(v);}
    public void setCamera(GeoMath.Point p,double z,double bearing){center=p;view.setCamera(p,z,bearing,0);prefetch(p);}
    public void setBrowseCamera(GeoMath.Point p,double z,double bearing,double pitch,double vehicleBearing){center=p;view.setCamera(p,z,bearing,pitch);prefetch(p);}
    public void setVehicle(GeoMath.Point p){view.setVehicle(p,Double.NaN);}
    public void setUnsnappedVehicle(GeoMath.Point p,double bearing){view.setVehicle(p,bearing);}
    public void followFrame(SmoothFollowCamera.Frame f){center=f.center;
        double zoom=junctionContext.isEmpty()?f.zoom:JunctionFraming.fitZoom(f.zoom,f.center,f.vehicle,junctionContext,view.getWidth(),view.getHeight(),viewportTop,viewportBottom,20);
        view.setCamera(f.center,zoom,f.bearing,f.tilt);view.setVehicle(f.vehicle,f.travelBearing);prefetch(f.center);}
    public void setFollowViewport(int top,int bottom,GeoMath.Point junction,List<GeoMath.Point> context){viewportTop=top;viewportBottom=bottom;junctionContext=context==null?Collections.emptyList():new ArrayList<>(context);if(junction!=null){junctionContext=new ArrayList<>(junctionContext);junctionContext.add(junction);}}
    public void previewRoute(List<GeoMath.Point> p,int top,int bottom,int side){view.fit(p,top,bottom,side);prefetch(view.center());}
    public void setConfirmedRouteProgress(double p){view.setProgressOnly(p);}
    public void setManeuverOffsets(double a,double b){view.setTurns(a,b);}
    public void setManeuverOffset(double a){view.setTurns(a,Double.NaN);}
    public void clearJunctionFraming(){junctionContext=Collections.emptyList();}
    public void updateCurrentRoad(GeoMath.Point p){}
    public void clearCurrentRoad(){}
    public void setBuildingContext(String endpoint,String source){}
    public void setDestination(GeoMath.Point p){destination=p;markers();}
    public void setDestinationLabel(String label){view.setDestinationLabel(label);}
    public void setEntrance(GeoMath.Point p){entrance=p;markers();}
    public void setParkedCar(GeoMath.Point p){parked=p;markers();}
    private void markers(){view.setMarkers(destination,entrance,parked);}
    public void setRouteEvents(List<RouteIntelligence.SpeedSection> speeds,List<RouteIntelligence.TrafficSection> traffic,boolean metric,long fetchedAt){view.setEvents(speeds,metric);}
    public void setTraffic(boolean v){traffic=v;if(!v)setLiveIncidents(null,0);}
    public boolean supportsLiveIncidents(){return traffic;}
    public void setLiveIncidents(List<LiveTrafficIncidents.Event> rows,long at){view.setIncidents(rows,at);}
    public void setSharedRoute(NativeMapScene scene,List<GeoMath.Point> path){setSharedRoute(scene,path,"");}
    public void setSharedRoute(NativeMapScene scene,List<GeoMath.Point> path,String provider){
        generation++;verifiedRoute=scene;current=scene;route=path==null?Collections.emptyList():path;view.setSceneOnly(scene);view.setRoute(route);view.setRoadside(Collections.emptyList());if(scene!=null)roadFurniture(scene);
        // The base map is independent of the route and stays; only the overlay changes.
        overlayAt=null;prefetch(center!=null?center:view.center());
    }
    public void setBrowseGeometry(NativeMapScene scene){if(!route.isEmpty())return;current=scene;view.setSceneOnly(scene);if(scene!=null)roadFurniture(scene);}
    private void roadFurniture(NativeMapScene s){
        int gen=generation;worker.execute(()->{try{
            if(roadside==null)roadside=MappedRoadside.load(activity);if(signals==null)signals=MappedSignals.load(activity);
            double[] b=s.bounds;List<int[]> rows=roadside.nativePoints(b[1],b[0],b[3],b[2]);Set<String> seen=new HashSet<>();
            for(int[] row:rows)if(row[2]==4)seen.add(row[0]+":"+row[1]);
            for(int[] row:signals.nearby(b[1],b[0],b[3],b[2],1000))if(seen.add(row[0]+":"+row[1]))rows.add(new int[]{row[0],row[1],4});
            activity.runOnUiThread(()->{if(!disposed&&gen==generation&&current==s)view.setRoadside(rows);});
        }catch(Exception ignored){}});
    }
    /**
     * Two layers, the same at every zoom:
     * 1. base map: the UK vector tiles (overzoomed past their top zoom), always drawn, never blank;
     * 2. overlay: Drivemate's exact road data on top, the verified route corridor while a native route
     *    is shown, otherwise the local road box at street zoom. The overlay never replaces the base.
     */
    public void prefetch(GeoMath.Point p){
        if(disposed||p==null)return;
        prefetchBase(p);
        prefetchOverlay(p);
    }
    private GeoMath.Point overlayAt;private boolean overlayBusy;
    private void prefetchOverlay(GeoMath.Point p){
        if(verifiedRoute!=null){if(current!=verifiedRoute){current=verifiedRoute;view.setSceneOnly(current);roadFurniture(current);}return;}
        // Browsing: the base map alone, identical at every zoom. Drivemate's exact road data is drawn
        // only along a route (above) and used for free-drive speed limits (setBrowseGeometry).
        if(!route.isEmpty()||!MapLayers.BROWSE_OVERLAY||view.zoom()<MapLayers.OVERLAY_MIN_ZOOM||overlayBusy)return;
        if(current!=null&&current.covers(p)&&overlayAt!=null&&GeoMath.distance(overlayAt,p)<MapLayers.OVERLAY_RELOAD_METRES)return;
        overlayBusy=true;overlayAt=p;int gen=generation;
        worker.execute(()->{
            NativeMapScene loaded=null;
            try{loaded=new NativeRegionalRouter(activity).roadContext(p).map;}catch(Exception ignored){}
            final NativeMapScene result=loaded;
            activity.runOnUiThread(()->{overlayBusy=false;
                if(disposed||gen!=generation||result==null||!route.isEmpty())return;
                current=result;view.setSceneOnly(result);roadFurniture(result);});
        });
    }
    private boolean basePending,baseDeferred;
    private void prefetchBase(GeoMath.Point p){
        double zoom=view.zoom();
        if(background!=null&&background.covers(p)&&backgroundAt!=null&&Math.abs(backgroundZoom-zoom)<1
                &&GeoMath.distance(backgroundAt,p)<MapLayers.baseReloadMetres(zoom))return;
        long now=SystemClock.elapsedRealtime();
        // Never drop a request: a busy or too-soon load is re-run as soon as it can be, for wherever the map is then.
        if(busy){basePending=true;return;}
        if(requestedAt>0&&now-requestedAt<MapLayers.BASE_MIN_INTERVAL_MS){
            if(!baseDeferred){baseDeferred=true;view.postDelayed(()->{baseDeferred=false;if(!disposed)prefetch(view.center());},MapLayers.BASE_MIN_INTERVAL_MS-(now-requestedAt));}
            return;}
        requestedAt=now;busy=true;backgroundAt=p;backgroundZoom=zoom;int w=view.getWidth(),h=view.getHeight();
        worker.execute(()->{
            NativeMapScene loaded=null;boolean detailFailed=false;String detailError="";
            try{loaded=tileSource.load(p,zoom,w,h);
                // Show what loaded, and keep filling in the missing tiles.
                if(tileSource.missingTiles()>0){detailFailed=true;detailError=MapLoadHealth.partialReason(tileSource.missingTiles());}
                else tileSource.warm(p);   // view is complete: pre-fetch the area around it in the background
            }catch(Exception failure){
                // Fall back to the coarse world overview, but remember why and retry the detail load.
                detailFailed=true;detailError=MapLoadHealth.reason(failure);
                try{loaded=tileSource.load(p,Math.min(6,zoom),w,h);}catch(Exception unavailable){}
            }
            final NativeMapScene result=loaded;final boolean failedDetail=detailFailed;final String failedReason=detailError;
            activity.runOnUiThread(()->{busy=false;loadFinished=true;
                if(basePending&&!disposed){basePending=false;view.post(()->prefetch(view.center()));}
               lastDetailError=failedDetail?failedReason:"";
                if(failedDetail&&!disposed){
                    long delay=MapLoadHealth.retryDelayMs(++detailRetries);
                    if(delay>0)view.postDelayed(()->{if(!disposed){backgroundAt=null;prefetch(view.center());}},delay);
                }else if(!failedDetail)detailRetries=0;
                view.setLoading(false);
                if(disposed||result==null)return;
                // Keep the previous picture unless the new one is complete or there was nothing.
                if(!failedDetail||background==null||!background.covers(view.center())){background=result;view.setBackgroundScene(result);}
                GeoMath.Point c=view.center();
                if(c!=null&&!result.covers(c)){backgroundAt=null;view.post(()->prefetch(view.center()));}
            });
        });
        if(background==null)view.setLoading(true);
    }
    public void prefetchRoute(List<GeoMath.Point> ahead){if(ahead!=null&&!ahead.isEmpty()&&view.center()!=null)prefetch(view.center());}
    public String cacheStats(){return (gl!=null?gl.stats()+" · GL frames "+gl.framesDrawn()+" · ":"")+"DriveMate regional + GitHub vector cache · frames "+view.frameCount()+" · mean CPU draw "+String.format(java.util.Locale.ROOT,"%.1f ms",view.meanDrawNanos()/1e6)+" · max "+String.format(java.util.Locale.ROOT,"%.1f ms",view.maxDrawNanos()/1e6);}
    // The GPU map keeps drawing while a dialog (permissions, settings) covers part of the screen: it pauses on stop, not pause.
    public void onStart(){if(gl!=null)gl.onResume();}public void onPause(){}public void onStop(){if(gl!=null)gl.onPause();}
    /** Coming back to the app: re-request tiles, since nothing else may move the camera. */
    public void onResume(){if(disposed)return;requestedAt=0;backgroundAt=null;overlayAt=null;GeoMath.Point c=view.center();if(c==null)c=center;if(c!=null)prefetch(c);view.invalidate();}
    public void onLowMemory(){
        // Drop the base map (it reloads); keep the route corridor, which is not re-fetchable here.
        background=null;backgroundAt=null;view.setBackgroundScene(null);
        if(route.isEmpty()){current=null;view.setSceneOnly(null);overlayAt=null;}
        GeoMath.Point c=view.center();if(c!=null)view.post(()->prefetch(c));
    }
    public void onSaveInstanceState(Bundle state){}
    public void onDestroy(){disposed=true;if(gl!=null)gl.dispose();generation++;worker.execute(tileSource::close);worker.shutdown();}
    static Bitmap makeVehicleArrow(){Bitmap b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Path path=new Path();path.moveTo(50,6);path.lineTo(84,87);path.lineTo(50,68);path.lineTo(16,87);path.close();p.setColor(0xff007ac9);c.drawPath(path,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);p.setColor(Color.WHITE);c.drawPath(path,p);return b;}
}
