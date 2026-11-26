package com.drivemate.app;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeMapRendererDeviceTest {
    @Test public void realRegionalRoadsAndRouteWithoutMapSdk()throws Exception{
        Instrumentation i=InstrumentationRegistry.getInstrumentation();Context app=i.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        NativeMapPreviewActivity a=(NativeMapPreviewActivity)i.startActivitySync(new Intent(app,NativeMapPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            long deadline=SystemClock.elapsedRealtime()+90000;AtomicBoolean loaded=new AtomicBoolean();
            while(SystemClock.elapsedRealtime()<deadline){i.runOnMainSync(()->loaded.set(a.scene!=null&&a.map.getWidth()>100));if(loaded.get())break;SystemClock.sleep(500);}
            assertTrue("Verified native scene did not load",loaded.get());
            File dir=new File(app.getExternalFilesDir(null),"unified-zoom");assertTrue(dir.isDirectory()||dir.mkdirs());
            for(boolean night:new boolean[]{false,true})for(int zoom:new int[]{14,17,19,21}){
                i.runOnMainSync(()->a.position(zoom,night,zoom>=17));i.waitForIdleSync();SystemClock.sleep(500);
                i.runOnMainSync(()->{
                    assertTrue(a.scene.checkedEdges>0);assertTrue("No visible source roads at "+zoom,a.map.roadsDrawn()>0);
                    assertEquals(1,a.map.routeDrawn());
                });
                Bitmap shot=i.getUiAutomation().takeScreenshot();assertNotNull(shot);
                try(FileOutputStream f=new FileOutputStream(new File(dir,"native-"+(night?"night":"day")+"-"+zoom+".png"))){assertTrue(shot.compress(Bitmap.CompressFormat.PNG,100,f));}finally{shot.recycle();}
            }
            i.runOnMainSync(()->{
                assertTrue(a.map.frameCount()>0);
                assertTrue(a.map.meanDrawNanos()>0);
                android.util.Log.i("UnifiedZoom","DriveMate native CPU draw frames="+a.map.frameCount()+" meanNs="+a.map.meanDrawNanos()+" maxNs="+a.map.maxDrawNanos());
            });
        }finally{i.runOnMainSync(a::finish);}
    }
}
