package com.drivemate.app;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class DmUnifiedZoomTest {
    @Test public void nativeRegionalRouteAndSearchWithoutProvider()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        NativeRegionalRouter router=new NativeRegionalRouter(app);
        DmUnifiedRegion region=new DmUnifiedRegion(app,true);
        DmTileEngine.Route sample=region.sample(true);
        TomTomApi.Route route=NativeRegionalRouter.convert(region,sample,sample.points.get(0),sample.points.get(sample.points.size()-1));
        assertEquals(NativeRegionalRouter.PROVIDER,route.provider);
        assertEquals(sample.ways.size(),route.nativeScene.checkedEdges);
        assertTrue(route.instructions.size()>=1);
        assertEquals("DESTINATION",route.instructions.get(route.instructions.size()-1).maneuver);
        double previous=-1;
        for(TomTomApi.Instruction step:route.instructions){assertTrue(step.routeOffsetMetres>previous);assertTrue(step.routeOffsetMetres<=route.lengthMetres+.01);previous=step.routeOffsetMetres;}
        assertTrue(route.trafficSections.isEmpty());
        // Lanes come only from mapped turn:lanes tags; a turn every lane serves is now shown (all lit).
        for(RouteIntelligence.LaneSection lanes:route.laneSections){
            assertTrue(lanes.trusted());assertTrue(lanes.start>=0);assertTrue(lanes.end<=route.lengthMetres+.01);
        }
        assertFalse(NavigationStartPolicy.previewOnly(route.provider));
        assertFalse("Mapped street search must work offline",router.search("Yew Street",sample.points.get(0)).isEmpty());
        // Recalculate a real graph route after the simulated driver advances.
        GeoMath.Point advanced=sample.points.get(Math.max(1,sample.points.size()/3));
        TomTomApi.Route rerouted=router.reroutePreview(advanced,sample.points.get(sample.points.size()-1));
        assertEquals(NativeRegionalRouter.PROVIDER,rerouted.provider);
        assertTrue(rerouted.nativeScene.checkedEdges>0);
        assertTrue(GeoMath.distance(rerouted.points.get(0),advanced)<=75);
        assertFalse(NavigationStartPolicy.previewOnly(rerouted.provider));
        try{router.preview(new GeoMath.Point(51,-2),new GeoMath.Point(51.01,-2));fail("Outside coverage routed");}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("No external fallback"));}
    }
    /** Owner report (2.2.2): typing in "Where to?" and searching closed the app. Drive the real UI the same way. */
    @Test public void searchTypingAndPickingDoNotCrash()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        engine(app,MapEngines.DEFAULT);
        MainActivity a=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            SystemClock.sleep(3000);
            java.lang.reflect.Field input=MainActivity.class.getDeclaredField("destinationInput");input.setAccessible(true);
            final android.widget.AutoCompleteTextView box=(android.widget.AutoCompleteTextView)input.get(a);
            ins.runOnMainSync(()->{box.requestFocus();android.view.inputmethod.InputMethodManager k=(android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE);if(k!=null)k.showSoftInput(box,0);});
            String typed="";for(char ch:"Yew Street".toCharArray()){typed+=ch;final String t=typed;ins.runOnMainSync(()->{box.setText(t);box.setSelection(t.length());});SystemClock.sleep(250);}
            SystemClock.sleep(3000);
            // Drag the map while the keyboard is up, as a driver would.
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("vectorMap");field.setAccessible(true);
            DriveMap live=(DriveMap)field.get(a);
            ins.runOnMainSync(()->live.setCamera(new GeoMath.Point(53.405,-2.16),12,0));SystemClock.sleep(1500);
            ins.runOnMainSync(()->live.setCamera(new GeoMath.Point(53.40,-2.20),14,0));SystemClock.sleep(1500);
            java.lang.reflect.Method search=MainActivity.class.getDeclaredMethod("searchDestination");search.setAccessible(true);
            ins.runOnMainSync(()->{try{search.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(8000);
            java.lang.reflect.Method pick=MainActivity.class.getDeclaredMethod("selectDestination",TomTomApi.Destination.class);pick.setAccessible(true);
            ins.runOnMainSync(()->{try{pick.invoke(a,new TomTomApi.Destination("Yew Street","Stockport",new GeoMath.Point(53.4038,-2.1702)));}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(12000);
            AtomicBoolean alive=new AtomicBoolean();ins.runOnMainSync(()->alive.set(!a.isFinishing()&&!a.isDestroyed()));
            assertTrue("Activity closed during search",alive.get());
        }finally{ins.runOnMainSync(a::finish);}
    }
    @Test public void actualLiveVectorRendererUsesSharedRoadsAtDrivingZoom()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        DmUnifiedRegion region=new DmUnifiedRegion(app,true);DmTileEngine.Route sample=region.sample(true);
        NativeMapScene geometry=region.nativeScene(sample);
        assertEquals(sample.ways.size(),geometry.checkedEdges);
        GeoMath.Point center=GeoMath.pointAtDistance(sample.points,sample.metres*.4);
        assertTrue(RouteMapPolicy.insideStockport(center));
        engine(app,MapEngines.CLASSIC);
        MainActivity a=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("vectorMap");field.setAccessible(true);
            NativeMapController live=(NativeMapController)field.get(a);assertNotNull(live);assertTrue(live.view() instanceof NativeMapView);
            ins.runOnMainSync(()->live.setSharedRoute(geometry,sample.points,NativeRegionalRouter.PROVIDER));
            for(double zoom:new double[]{17,19,21}){
                ins.runOnMainSync(()->live.setCamera(center,zoom,0));
                waitForPaint(ins,(NativeMapView)live.view(),zoom);
                capture(ins,app,a,"live-zoom-"+(int)zoom+".png");
            }
        }finally{ins.runOnMainSync(a::finish);}
    }
    @Test public void actualStockportGpuRoadsAndRouteAtEveryZoom()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        NativeMapPreviewActivity a=(NativeMapPreviewActivity)ins.startActivitySync(new Intent(app,NativeMapPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            long deadline=SystemClock.elapsedRealtime()+90000;AtomicBoolean ready=new AtomicBoolean();
            while(SystemClock.elapsedRealtime()<deadline){ins.runOnMainSync(()->ready.set(a.scene!=null&&a.scene.checkedEdges>0));if(ready.get())break;SystemClock.sleep(500);}
            assertTrue("Exact source scene failed to load",ready.get());
            for(boolean night:new boolean[]{false,true})for(double zoom:new double[]{14,17,19,21}){
                ins.runOnMainSync(()->a.position(zoom,night,zoom>=17));
                waitForPaint(ins,a.map,zoom);capture(ins,app,a,(night?"night-":"day-")+"zoom-"+(int)zoom+".png");
                android.util.Log.i("UnifiedZoom","PASS native Stockport "+(night?"night":"day")+" zoom "+zoom+" roads and verified route");
            }
        }finally{ins.runOnMainSync(a::finish);}
    }
    static void engine(Context app,String id){app.getSharedPreferences("drivemate_settings",Context.MODE_PRIVATE).edit().putString(MapEngines.PREF,id).commit();}
    /** The default GPU engine draws real roads offline from DriveMate's own tiles, with the route on top. */
    @Test public void mapLibreGpuMapDrawsRoadsAndRoute()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        DmUnifiedRegion region=new DmUnifiedRegion(app,true);DmTileEngine.Route sample=region.sample(true);
        GeoMath.Point center=GeoMath.pointAtDistance(sample.points,sample.metres*.4);
        engine(app,MapEngines.MAPLIBRE);
        MainActivity a=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("vectorMap");field.setAccessible(true);
            DriveMap live=(DriveMap)field.get(a);assertNotNull(live);
            assertTrue("GPU engine not in use: "+live.engineName(),live instanceof MapLibreMapController);
            ins.runOnMainSync(()->{live.setSharedRoute(null,sample.points,NativeRegionalRouter.PROVIDER);live.setCamera(center,17,0);});
            long deadline=SystemClock.elapsedRealtime()+90000;AtomicBoolean roads=new AtomicBoolean();
            while(SystemClock.elapsedRealtime()<deadline){ins.runOnMainSync(()->roads.set(live.hasRenderedRoads()));if(roads.get())break;SystemClock.sleep(1000);}
            java.util.concurrent.atomic.AtomicReference<String> why=new java.util.concurrent.atomic.AtomicReference<>();ins.runOnMainSync(()->why.set(live.cacheStats()));
            assertTrue("MapLibre drew no roads offline: "+why.get(),roads.get());
            for(double zoom:new double[]{14,17,19}){ins.runOnMainSync(()->live.setCamera(center,zoom,0));SystemClock.sleep(4000);capture(ins,app,a,"maplibre-zoom-"+(int)zoom+".png");}
            ins.runOnMainSync(()->live.setBrowseCamera(center,18,40,45,40));SystemClock.sleep(4000);capture(ins,app,a,"maplibre-tilted.png");
        }finally{ins.runOnMainSync(a::finish);engine(app,MapEngines.DEFAULT);}
    }
    private static void waitForPaint(Instrumentation ins,NativeMapView map,double zoom)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+90000;AtomicBoolean ok=new AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong before=new java.util.concurrent.atomic.AtomicLong();
        ins.runOnMainSync(()->{before.set(map.frameCount());map.invalidate();});
        while(SystemClock.elapsedRealtime()<deadline){
            ins.runOnMainSync(()->ok.set(map.frameCount()>before.get()&&Math.abs(map.zoom()-zoom)<.01&&map.roadsDrawn()>0&&map.routeDrawn()>0));
            if(ok.get())break;SystemClock.sleep(500);
        }
        assertTrue("No actual native rendered roads and route at zoom "+zoom,ok.get());
    }
    private static void capture(Instrumentation ins,Context app,android.app.Activity activity,String name)throws Exception{
        File dir=new File(app.getExternalFilesDir(null),"unified-zoom");assertTrue(dir.isDirectory()||dir.mkdirs());
        Bitmap shot=NativeScreenshot.capture(ins,activity);assertNotNull(shot);
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(shot.compress(Bitmap.CompressFormat.PNG,100,out));}finally{shot.recycle();}
    }
}
