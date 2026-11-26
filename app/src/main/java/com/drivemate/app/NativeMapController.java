package com.drivemate.app;
import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.util.*;
import java.util.concurrent.*;

/** Main map controller: native rendering and regional data, no external map SDK. */
final class NativeMapController {
    interface Listener{void status(RoadDisplayHealth.State state,int count);}
    private final Activity activity;private final NativeMapView view;private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private NativeMapScene current;
    private final NativeTileSource tileSource;
    private NativeMapScene verifiedRoute;
    private MappedRoadside roadside;
    private MappedSignals signals;
    private double lastZoom=-1;
    private int viewportTop,viewportBottom;
    private List<GeoMath.Point> junctionContext=Collections.emptyList();
    private List<GeoMath.Point> route=Collections.emptyList();
    private GeoMath.Point destination,entrance,parked,center;
    private boolean night,traffic,disposed,busy;
    private int generation;
    private long requestedAt;
    private GeoMath.Point lastRequested;
    private RoadDisplayHealth.State reported;
    NativeMapController(Activity activity,Listener listener){
        this.activity=activity;this.listener=listener;view=new NativeMapView(activity);tileSource=new NativeTileSource(activity);
        view.setCameraChanged(()->prefetch(view.center()));
        view.setPaintListener(()->{
            RoadDisplayHealth.State state=view.roadsDrawn()>0?RoadDisplayHealth.State.READY:RoadDisplayHealth.State.FAILED;
            if(state!=reported){reported=state;view.post(()->{if(!disposed)listener.status(state,view.roadsDrawn());});}
        });
    }
    View view(){return view;}
    void setOnUserGesture(Runnable r){view.setGesture(r);}
    boolean nightMode(){return night;}
    boolean hasRenderedRoads(){return view.roadsDrawn()>0;}
    double currentZoom(){return view.zoom();}
    void setVisible(boolean v){view.setVisibility(v?View.VISIBLE:View.INVISIBLE);}
    String geometryDescription(){return current==null?"DriveMate native · regional detail unavailable":current.checkedEdges>0?"DriveMate native · verified route roads":"DriveMate native · regional roads";}
    String routeAlignmentDiagnostic(){return current!=null&&current.checkedEdges>0?current.checkedEdges+" exact source edges":"unverified";}
    void setHighDetailEnabled(boolean enabled){}
    void setBuildings3D(boolean enabled){view.setBuildings(enabled);}
    void setNavigationDetail(boolean navigating){}
    void setNightMode(boolean v){night=v;view.setNight(v);}
    void setCamera(GeoMath.Point p,double z,double bearing){center=p;view.setCamera(p,z,bearing,0);prefetch(p);}
    void setBrowseCamera(GeoMath.Point p,double z,double bearing,double pitch,double vehicleBearing){center=p;view.setCamera(p,z,bearing,pitch);prefetch(p);}
    void setVehicle(GeoMath.Point p){view.setVehicle(p,Double.NaN);}
    void setUnsnappedVehicle(GeoMath.Point p,double bearing){view.setVehicle(p,bearing);}
    void followFrame(SmoothFollowCamera.Frame f){center=f.center;
        double zoom=junctionContext.isEmpty()?f.zoom:JunctionFraming.fitZoom(f.zoom,f.center,f.vehicle,junctionContext,view.getWidth(),view.getHeight(),viewportTop,viewportBottom,20);
        view.setCamera(f.center,zoom,f.bearing,f.tilt);view.setVehicle(f.vehicle,f.travelBearing);prefetch(f.center);}
    void setFollowViewport(int top,int bottom,GeoMath.Point junction,List<GeoMath.Point> context){viewportTop=top;viewportBottom=bottom;junctionContext=context==null?Collections.emptyList():new ArrayList<>(context);if(junction!=null){junctionContext=new ArrayList<>(junctionContext);junctionContext.add(junction);}}
    void previewRoute(List<GeoMath.Point> p,int top,int bottom,int side){view.fit(p,top,bottom,side);}
    void setConfirmedRouteProgress(double p){view.setProgressOnly(p);}
    void setManeuverOffsets(double a,double b){view.setTurns(a,b);}
    void setManeuverOffset(double a){view.setTurns(a,Double.NaN);}
    void clearJunctionFraming(){junctionContext=Collections.emptyList();}
    void updateCurrentRoad(GeoMath.Point p){}
    void clearCurrentRoad(){}
    void setBuildingContext(String endpoint,String source){}
    void setDestination(GeoMath.Point p){destination=p;markers();}
    void setEntrance(GeoMath.Point p){entrance=p;markers();}
    void setParkedCar(GeoMath.Point p){parked=p;markers();}
    private void markers(){view.setMarkers(destination,entrance,parked);}
    void setRouteEvents(List<RouteIntelligence.SpeedSection> speeds,List<RouteIntelligence.TrafficSection> traffic,boolean metric,long fetchedAt){view.setEvents(speeds,metric);}
    void setTraffic(boolean v){traffic=v;if(!v)setLiveIncidents(null,0);}
    boolean supportsLiveIncidents(){return traffic;}
    void setLiveIncidents(List<LiveTrafficIncidents.Event> rows,long at){view.setIncidents(rows,at);}
    void setSharedRoute(NativeMapScene scene,List<GeoMath.Point> path){setSharedRoute(scene,path,"");}
    void setSharedRoute(NativeMapScene scene,List<GeoMath.Point> path,String provider){
        generation++;verifiedRoute=scene;current=scene;route=path==null?Collections.emptyList():path;view.setSceneOnly(scene);view.setRoute(route);view.setRoadside(Collections.emptyList());if(scene!=null)roadFurniture(scene);
        if(scene==null&&route.isEmpty()){requestedAt=0;prefetch(center);}
    }
    void setBrowseGeometry(NativeMapScene scene){if(!route.isEmpty())return;current=scene;view.setSceneOnly(scene);if(scene!=null)roadFurniture(scene);}
    private void roadFurniture(NativeMapScene s){
        int gen=generation;worker.execute(()->{try{
            if(roadside==null)roadside=MappedRoadside.load(activity);if(signals==null)signals=MappedSignals.load(activity);
            double[] b=s.bounds;List<int[]> rows=roadside.nativePoints(b[1],b[0],b[3],b[2]);Set<String> seen=new HashSet<>();
            for(int[] row:rows)if(row[2]==4)seen.add(row[0]+":"+row[1]);
            for(int[] row:signals.nearby(b[1],b[0],b[3],b[2],1000))if(seen.add(row[0]+":"+row[1]))rows.add(new int[]{row[0],row[1],4});
            activity.runOnUiThread(()->{if(!disposed&&gen==generation&&current==s)view.setRoadside(rows);});
        }catch(Exception ignored){}});
    }
    void prefetch(GeoMath.Point p){
        if(disposed||p==null)return;
        if(verifiedRoute!=null&&verifiedRoute.covers(p)){if(current!=verifiedRoute){current=verifiedRoute;view.setSceneOnly(current);roadFurniture(current);}return;}
        double zoom=view.zoom();
        if(current!=null&&current.covers(p)&&Math.abs(lastZoom-zoom)<1&&lastRequested!=null&&GeoMath.distance(lastRequested,p)<Math.max(100,4000/Math.pow(2,Math.max(0,zoom-13))))return;
        long now=SystemClock.elapsedRealtime();if(busy||requestedAt>0&&now-requestedAt<2000)return;
        requestedAt=now;lastRequested=p;lastZoom=zoom;busy=true;int gen=generation,w=view.getWidth(),h=view.getHeight();
        worker.execute(()->{
            NativeMapScene loaded=null;
            if(zoom>=15)try{loaded=new NativeRegionalRouter(activity).roadContext(p).map;}catch(Exception ignored){}
            if(loaded==null)try{loaded=tileSource.load(p,zoom,w,h);}catch(Exception ignored){
                try{loaded=tileSource.load(p,Math.min(6,zoom),w,h);}catch(Exception unavailable){}
            }
            final NativeMapScene result=loaded;
            activity.runOnUiThread(()->{busy=false;if(disposed||gen!=generation||result==null)return;
                GeoMath.Point c=view.center();if(c!=null&&result.covers(c)&&Math.abs(view.zoom()-zoom)<1.5){current=result;view.setSceneOnly(result);view.setRoadside(Collections.emptyList());roadFurniture(result);}
            });
        });
    }
    void prefetchRoute(List<GeoMath.Point> ahead){if(ahead!=null&&!ahead.isEmpty()&&view.center()!=null)prefetch(view.center());}
    String cacheStats(){return "DriveMate regional + GitHub vector cache · frames "+view.frameCount()+" · mean CPU draw "+String.format(java.util.Locale.ROOT,"%.1f ms",view.meanDrawNanos()/1e6)+" · max "+String.format(java.util.Locale.ROOT,"%.1f ms",view.maxDrawNanos()/1e6);}
    void onStart(){}void onResume(){}void onPause(){}void onStop(){}
    void onLowMemory(){if(route.isEmpty()){current=null;view.setSceneOnly(null);}}
    void onSaveInstanceState(Bundle state){}
    void onDestroy(){disposed=true;generation++;worker.execute(tileSource::close);worker.shutdown();}
    static Bitmap makeVehicleArrow(){Bitmap b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Path path=new Path();path.moveTo(50,6);path.lineTo(84,87);path.lineTo(50,68);path.lineTo(16,87);path.close();p.setColor(0xff007ac9);c.drawPath(path,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);p.setColor(Color.WHITE);c.drawPath(path,p);return b;}
}
