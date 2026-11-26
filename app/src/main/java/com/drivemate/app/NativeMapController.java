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
    private List<GeoMath.Point> route=Collections.emptyList();
    private GeoMath.Point destination,entrance,parked,center;
    private boolean night,traffic,disposed,busy;
    private int generation;
    private long requestedAt;
    private GeoMath.Point lastRequested;
    private RoadDisplayHealth.State reported;
    NativeMapController(Activity activity,Listener listener){
        this.activity=activity;this.listener=listener;view=new NativeMapView(activity);
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
    void followFrame(SmoothFollowCamera.Frame f){center=f.center;view.setCamera(f.center,f.zoom,f.bearing,f.tilt);view.setVehicle(f.vehicle,f.travelBearing);}
    void setFollowViewport(int top,int bottom,GeoMath.Point junction,List<GeoMath.Point> context){}
    void previewRoute(List<GeoMath.Point> p,int top,int bottom,int side){view.fit(p,top,bottom,side);}
    void setConfirmedRouteProgress(double p){view.setProgressOnly(p);}
    void setManeuverOffsets(double a,double b){view.setTurns(a,b);}
    void setManeuverOffset(double a){view.setTurns(a,Double.NaN);}
    void clearJunctionFraming(){}
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
        generation++;current=scene;route=path==null?Collections.emptyList():path;view.setSceneOnly(scene);view.setRoute(route);
        if(scene==null&&route.isEmpty()){requestedAt=0;prefetch(center);}
    }
    void setBrowseGeometry(NativeMapScene scene){if(!route.isEmpty())return;current=scene;view.setSceneOnly(scene);}
    void prefetch(GeoMath.Point p){
        if(disposed||p==null||!route.isEmpty())return;
        if(!RouteMapPolicy.insideStockport(p)){if(current!=null){current=null;view.setSceneOnly(null);}return;}
        if(current!=null&&current.covers(p))return;
        long now=SystemClock.elapsedRealtime();if(busy||requestedAt>0&&now-requestedAt<5000&&lastRequested!=null&&GeoMath.distance(lastRequested,p)<250)return;
        requestedAt=now;lastRequested=p;busy=true;int gen=generation;
        worker.execute(()->{
            NativeMapScene loaded=null;try{loaded=new NativeRegionalRouter(activity).roadContext(p).map;}catch(Exception ignored){}
            final NativeMapScene result=loaded;
            activity.runOnUiThread(()->{busy=false;if(disposed||gen!=generation||!route.isEmpty()||result==null)return;
                GeoMath.Point c=view.center();if(c!=null&&result.covers(c)){current=result;view.setSceneOnly(result);}
            });
        });
    }
    void prefetchRoute(List<GeoMath.Point> ahead){}
    String cacheStats(){return "Verified packaged Cheadle/Stockport regional data; nationwide tile decoding pending.";}
    void onStart(){}void onResume(){}void onPause(){}void onStop(){}
    void onLowMemory(){if(route.isEmpty()){current=null;view.setSceneOnly(null);}}
    void onSaveInstanceState(Bundle state){}
    void onDestroy(){disposed=true;generation++;worker.shutdownNow();}
    static Bitmap makeVehicleArrow(){Bitmap b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Path path=new Path();path.moveTo(50,6);path.lineTo(84,87);path.lineTo(50,68);path.lineTo(16,87);path.close();p.setColor(0xff007ac9);c.drawPath(path,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);p.setColor(Color.WHITE);c.drawPath(path,p);return b;}
}
