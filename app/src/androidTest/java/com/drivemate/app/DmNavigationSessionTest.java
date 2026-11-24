package com.drivemate.app;
import android.content.Context;
import android.net.ConnectivityManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class DmNavigationSessionTest {
    @Test public void simulateOfflineMissedTurnsAndRestart()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        android.content.res.AssetManager assets=app.getAssets();
        byte[] raw=DmTileEngine.read(assets.open("dmtiles/manifest.json"),2_000_000);
        String hash=new String(DmTileEngine.read(assets.open("dmtiles/manifest.sha256"),128)).trim();
        File root=new File(app.getCacheDir(),"dm-session-"+System.nanoTime());assertTrue(root.mkdirs());
        DmTileDiskSource online=new DmTileDiskSource(root,raw,hash,8_000_000,name->assets.open("dmtiles/"+name+".bin"));
        DmNavigationSession start=new DmNavigationSession(new DmTileEngine(raw,hash,online),online,new File(root,"journey.json"),hash);
        GeoMath.Point a=new GeoMath.Point(51.209,-2.647),b=new GeoMath.Point(51.207,-2.641),dev=new GeoMath.Point(51.214,-2.646);
        start.plan(a,b,true,48.3,8_000_000);
        DmTileDiskSource offline=new DmTileDiskSource(root,raw,hash,8_000_000,null);
        DmNavigationSession session=new DmNavigationSession(new DmTileEngine(raw,hash,offline),offline,new File(root,"journey.json"),hash);
        DmTileEngine.Route path=session.restore(a);long time=1000;double length=GeoMath.length(path.points);
        for(int i=0;i<1000;i++){
            GeoMath.Point position=GeoMath.pointAtDistance(path.points,length*i/1000.0);
            DmNavigationSession.Update u=session.fix(position,5,time+=1000,false);
            assertTrue(u.status==DmNavigationSession.Status.ON_ROUTE||u.status==DmNavigationSession.Status.ARRIVAL_PREVIEW);
        }
        assertEquals(DmNavigationSession.Status.UNCERTAIN,session.fix(dev,100,time+=1000,false).status);
        assertEquals(DmNavigationSession.Status.OFF_ROUTE,session.fix(dev,5,time+=1000,false).status);
        assertEquals(DmNavigationSession.Status.OFF_ROUTE,session.fix(dev,5,time+=1000,false).status);
        DmNavigationSession.Update reroute=session.fix(dev,5,time+=1000,false);
        assertEquals(DmNavigationSession.Status.REROUTED,reroute.status);
        assertTrue(reroute.route.metres>0);
        GeoMath.Point far=new GeoMath.Point(53.381,-1.47);
        for(int i=0;i<2;i++)session.fix(far,5,time+=15000,false);
        assertEquals(DmNavigationSession.Status.COVERAGE_MISSING,session.fix(far,5,time+=15000,false).status);
        DmNavigationSession reopened=new DmNavigationSession(new DmTileEngine(raw,hash,offline),offline,new File(root,"journey.json"),hash);
        assertTrue(reopened.restore(dev).metres>0);
        android.util.Log.i("OfflineRoutingPilot","Integrated session: 1000 GPS fixes, offline diversion, coverage failure, restart passed");
        reopened.clear();
    }
}
