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
    @Test public void actualStockportGpuRoadsAndRouteAtEveryZoom()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        Intent intent=new Intent(app,DmUnifiedPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("stockport",true);
        DmUnifiedPreviewActivity a=(DmUnifiedPreviewActivity)ins.startActivitySync(intent);
        File dir=new File(app.getExternalFilesDir(null),"unified-zoom");assertTrue(dir.isDirectory()||dir.mkdirs());
        try{
            for(double zoom:new double[]{14.0,17.0,19.0,21.0}){
                long deadline=SystemClock.elapsedRealtime()+90000;AtomicBoolean ok=new AtomicBoolean();
                while(SystemClock.elapsedRealtime()<deadline){
                    ins.runOnMainSync(()->{if(a.checkedEdges()>0){if(a.rendered(zoom))ok.set(true);else a.position(zoom);}});
                    if(ok.get())break;SystemClock.sleep(1000);
                }
                assertTrue("No actual rendered roads and route at zoom "+zoom,ok.get());
                Bitmap b=ins.getUiAutomation().takeScreenshot();assertNotNull(b);
                try(FileOutputStream out=new FileOutputStream(new File(dir,"zoom-"+(int)zoom+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
                android.util.Log.i("UnifiedZoom","PASS Stockport zoom "+zoom+" rendered roads and verified shared route");
            }
        }finally{ins.runOnMainSync(a::finish);}
    }
}
