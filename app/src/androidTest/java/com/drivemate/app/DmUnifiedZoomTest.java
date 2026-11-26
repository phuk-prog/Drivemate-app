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
        assertTrue(route.trafficSections.isEmpty());assertTrue(route.laneSections.isEmpty());
        assertTrue(NavigationStartPolicy.previewOnly(route.provider));
        assertFalse("Mapped street search must work offline",router.search("Yew Street",sample.points.get(0)).isEmpty());
        try{router.preview(new GeoMath.Point(51,-2),new GeoMath.Point(51.01,-2));fail("Outside coverage routed");}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("No external fallback"));}
    }
    @Test public void actualLiveVectorRendererUsesSharedRoadsAtDrivingZoom()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        DmUnifiedRegion region=new DmUnifiedRegion(app,true);DmTileEngine.Route sample=region.sample(true);
        NativeMapScene geometry=region.nativeScene(sample);
        assertEquals(sample.ways.size(),geometry.checkedEdges);
        GeoMath.Point center=GeoMath.pointAtDistance(sample.points,sample.metres*.4);
        assertTrue(RouteMapPolicy.insideStockport(center));
        MainActivity a=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("vectorMap");field.setAccessible(true);
            NativeMapController live=(NativeMapController)field.get(a);assertNotNull(live);assertTrue(live.view() instanceof NativeMapView);
            ins.runOnMainSync(()->live.setSharedRoute(geometry,sample.points,NativeRegionalRouter.PROVIDER));
            for(double zoom:new double[]{17,19,21}){
                ins.runOnMainSync(()->live.setCamera(center,zoom,0));
                waitForPaint(ins,(NativeMapView)live.view(),zoom);
                capture(ins,app,"live-zoom-"+(int)zoom+".png");
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
                waitForPaint(ins,a.map,zoom);capture(ins,app,(night?"night-":"day-")+"zoom-"+(int)zoom+".png");
                android.util.Log.i("UnifiedZoom","PASS native Stockport "+(night?"night":"day")+" zoom "+zoom+" roads and verified route");
            }
        }finally{ins.runOnMainSync(a::finish);}
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
    private static void capture(Instrumentation ins,Context app,String name)throws Exception{
        File dir=new File(app.getExternalFilesDir(null),"unified-zoom");assertTrue(dir.isDirectory()||dir.mkdirs());
        Bitmap shot=ins.getUiAutomation().takeScreenshot();assertNotNull(shot);
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(shot.compress(Bitmap.CompressFormat.PNG,100,out));}finally{shot.recycle();}
    }
}
