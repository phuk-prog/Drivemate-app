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
    @Test public void actualLiveVectorRendererUsesSharedRoadsAtDrivingZoom()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        final DmTileEngine.Route sample;final DmUnifiedGeometry geometry;
        { DmUnifiedRegion region=new DmUnifiedRegion(app,true);sample=region.sample(true);
          geometry=region.geometry(sample); }
        // No second preview loader; no graph object is needed once route geometry is verified.
        GeoMath.Point center=GeoMath.pointAtDistance(sample.points,sample.metres*.4);
        assertTrue(RouteMapPolicy.insideStockport(center));
        Intent intent=new Intent(app,DmUnifiedPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("stockport",true).putExtra("live_renderer_test_host",true);
        DmUnifiedPreviewActivity activity=(DmUnifiedPreviewActivity)ins.startActivitySync(intent);
        final VectorDriveMap[] live=new VectorDriveMap[1];
        try(DriveTileServer server=new DriveTileServer(app)){
            ins.runOnMainSync(()->{
                live[0]=new VectorDriveMap(activity,null,server,"",false,null);
                activity.setContentView(live[0].view());live[0].onStart();live[0].onResume();
                live[0].setSharedRoute(geometry,sample.points);live[0].setNavigationDetail(true);live[0].setCamera(center,17,0);
            });
            try{
                for(double zoom:new double[]{17,19,21}){
                    ins.runOnMainSync(()->live[0].setCamera(center,zoom,0));
                    long deadline=SystemClock.elapsedRealtime()+90000;AtomicBoolean ok=new AtomicBoolean();
                    while(SystemClock.elapsedRealtime()<deadline){ins.runOnMainSync(()->ok.set(live[0].sharedRouteRendered(zoom)));if(ok.get())break;SystemClock.sleep(1000);}
                    assertTrue("Live route and shared roads missing at zoom "+zoom,ok.get());
                    File dir=new File(app.getExternalFilesDir(null),"unified-zoom");assertTrue(dir.isDirectory()||dir.mkdirs());
                    Bitmap image=ins.getUiAutomation().takeScreenshot();assertNotNull(image);
                    try(FileOutputStream out=new FileOutputStream(new File(dir,"live-zoom-"+(int)zoom+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
                }
            }finally{ins.runOnMainSync(()->{if(live[0]!=null){live[0].onPause();live[0].onStop();live[0].onDestroy();}});}
        }finally{ins.runOnMainSync(activity::finish);}
    }
    @Test public void actualStockportGpuRoadsAndRouteAtEveryZoom()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        Intent intent=new Intent(app,DmUnifiedPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("stockport",true);
        DmUnifiedPreviewActivity a=(DmUnifiedPreviewActivity)ins.startActivitySync(intent);
        File dir=new File(app.getExternalFilesDir(null),"unified-zoom");assertTrue(dir.isDirectory()||dir.mkdirs());
        try{
            for(boolean night:new boolean[]{false,true}){
              if(night)ins.runOnMainSync(()->a.setNight(true));
              for(double zoom:new double[]{14.0,17.0,19.0,21.0}){
                long deadline=SystemClock.elapsedRealtime()+90000;AtomicBoolean ok=new AtomicBoolean(),positioned=new AtomicBoolean();
                while(SystemClock.elapsedRealtime()<deadline){
                    ins.runOnMainSync(()->{if(a.readyForZoom()){if(!positioned.getAndSet(true))a.position(zoom);else if(a.rendered(zoom))ok.set(true);}});
                    if(ok.get())break;SystemClock.sleep(1000);
                }
                assertTrue("No actual rendered roads and route at zoom "+zoom,ok.get());
                Bitmap b=ins.getUiAutomation().takeScreenshot();assertNotNull(b);
                try(FileOutputStream out=new FileOutputStream(new File(dir,(night?"night-":"day-")+"zoom-"+(int)zoom+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
                android.util.Log.i("UnifiedZoom","PASS Stockport "+(night?"night":"day")+" zoom "+zoom+" rendered roads and verified shared route");
            }
              }
        }finally{ins.runOnMainSync(a::finish);}
    }
}
