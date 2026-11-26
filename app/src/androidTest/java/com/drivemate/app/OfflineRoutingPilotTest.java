package com.drivemate.app;

import android.content.Context;
import android.net.ConnectivityManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OfflineRoutingPilotTest{
    @Test public void realDownloadedGraphRoutesWithoutNetwork()throws Exception{
        Context test=InstrumentationRegistry.getInstrumentation().getContext(),app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConnectivityManager cm=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNull("Disable emulator network before this test",cm.getActiveNetwork());
        File db=new File(app.getCacheDir(),"wells-routing-pilot.sqlite");
        try(InputStream in=test.getAssets().open("wells-routing.sqlite");OutputStream out=new FileOutputStream(db)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        JSONObject fixture;
        try(InputStream in=test.getAssets().open("wells-routing.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);fixture=new JSONObject(out.toString("UTF-8"));}
        assertEquals(fixture.getLong("database_bytes"),db.length());
        try(LocalRoutingPilot roads=new LocalRoutingPilot(db,fixture.getString("database_sha256"))){
            JSONArray samples=fixture.getJSONArray("samples");assertTrue(samples.length()>=2);
            for(int i=0;i<samples.length();i++){JSONObject s=samples.getJSONObject(i);JSONArray a=s.getJSONArray("start"),b=s.getJSONArray("end");
                LocalRoutingPilot.Result result=roads.route(new GeoMath.Point(a.getDouble(0),a.getDouble(1)),new GeoMath.Point(b.getDouble(0),b.getDouble(1)));
                assertTrue(result.expanded<=80000);
                assertTrue(result.elapsedMs>=0);
                assertTrue(result.points.size()>2);assertEquals(s.getJSONObject("reference").getDouble("distance_km"),result.metres/1000,0.02);
                assertEquals(s.getJSONObject("reference").getDouble("minutes"),result.seconds/60,0.06);
                android.util.Log.i("OfflineRoutingPilot","sample "+i+" metres="+result.metres+" seconds="+result.seconds+" expanded="+result.expanded+" elapsed_ms="+result.elapsedMs);
            }
        }
        try{new LocalRoutingPilot(db,"0".repeat(64));fail("Accepted corrupt digest");}catch(IOException expected){}
        assertFalse(LocalRoutingPilot.allowed(1,3,5,Collections.singletonList(new long[]{5,1})));
        assertFalse(LocalRoutingPilot.allowed(1,3,7,Collections.singletonList(new long[]{5,2})));
        assertTrue(LocalRoutingPilot.allowed(1,3,5,Collections.singletonList(new long[]{5,2})));
    }
}
