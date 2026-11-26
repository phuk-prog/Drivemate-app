package com.drivemate.app;
import android.app.Instrumentation;
import android.content.*;
import android.net.ConnectivityManager;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class NativeMainMapDeviceTest {
    @Test public void mainApplicationUsesOriginalMapWithoutNetwork()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        // Real bundled world PMTiles, not a synthetic decoder-only fixture.
        try(NativeTileSource tiles=new NativeTileSource(app)){
            NativeMapScene world=tiles.load(new GeoMath.Point(53.4,-2.16),5,320,480);
            assertFalse("Bundled world map did not decode",world.shapes.isEmpty());
            assertEquals(0,world.checkedEdges);
        }
        DmUnifiedRegion region=new DmUnifiedRegion(app,true);DmTileEngine.Route path=region.sample(true);
        NativeMapScene scene=region.nativeScene(path);
        MainActivity a=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            Field field=MainActivity.class.getDeclaredField("vectorMap");field.setAccessible(true);
            NativeMapController map=(NativeMapController)field.get(a);
            assertNotNull(map);assertTrue(map.view() instanceof NativeMapView);
            ins.runOnMainSync(()->{map.setSharedRoute(scene,path.points,NativeRegionalRouter.PROVIDER);map.setCamera(path.points.get(path.points.size()/2),17,0);});
            long deadline=SystemClock.elapsedRealtime()+15000;AtomicBoolean ready=new AtomicBoolean();
            while(SystemClock.elapsedRealtime()<deadline){ins.runOnMainSync(()->ready.set(map.hasRenderedRoads()&&((NativeMapView)map.view()).routeDrawn()>0));if(ready.get())break;SystemClock.sleep(250);}
            assertTrue("Main application native map did not paint source roads and route",ready.get());
        }finally{ins.runOnMainSync(a::finish);}
    }
}
