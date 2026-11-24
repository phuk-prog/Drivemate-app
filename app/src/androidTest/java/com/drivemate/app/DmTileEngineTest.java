package com.drivemate.app;
import android.content.Context;
import android.graphics.*;
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
public class DmTileEngineTest{
    @Test public void originalTilesOfflineRoutesSearchAndRender()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext(),test=InstrumentationRegistry.getInstrumentation().getContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        android.content.res.AssetManager assets=app.getAssets();
        byte[] raw=DmTileEngine.read(assets.open("dmtiles/manifest.json"),2000000);
        String hash=new String(DmTileEngine.read(assets.open("dmtiles/manifest.sha256"),128)).trim();
        DmTileEngine engine=new DmTileEngine(raw,hash,name->assets.open("dmtiles/"+name));
        engine.retainJourney(engine.keys());assertTrue(engine.cachedTiles()>1);
        JSONObject fixture=new JSONObject(new String(DmTileEngine.read(test.getAssets().open("wells-routing.json"),1000000)));
        JSONArray samples=fixture.getJSONArray("samples");assertTrue(samples.length()>=2);
        DmTileEngine.Route result=null;
        for(int i=0;i<samples.length();i++){JSONObject s=samples.getJSONObject(i);JSONArray a=s.getJSONArray("start"),b=s.getJSONArray("end");
            result=engine.route(new GeoMath.Point(a.getDouble(0),a.getDouble(1)),new GeoMath.Point(b.getDouble(0),b.getDouble(1)));
            assertEquals(s.getJSONObject("reference").getDouble("distance_km"),result.metres/1000,0.025);
            assertEquals(s.getJSONObject("reference").getDouble("minutes"),result.seconds/60,0.07);
            android.util.Log.i("OfflineRoutingPilot","DM tile sample "+i+" metres="+result.metres+" ms="+result.ms+" cells="+result.cells.size());
        }
        assertNotNull(result);assertTrue(engine.features(engine.keys()).size()>100);
        assertTrue(engine.search("Wells",engine.keys()).size()>0);
        try{engine.route(new GeoMath.Point(53.38,-1.47),new GeoMath.Point(51.21,-2.64));fail("Invented route outside coverage");}catch(DmTileEngine.CoverageException expected){}
        final DmTileEngine.Route r=result;final List<JSONArray> features=engine.features(engine.keys());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            DmPilotMapView view=new DmPilotMapView(app,features,r.points);view.layout(0,0,500,500);
            Bitmap b=Bitmap.createBitmap(500,500,Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));
            int blue=0;for(int y=0;y<500;y++)for(int x=0;x<500;x++)if(b.getPixel(x,y)==Color.rgb(33,105,220))blue++;
            assertTrue("Route not rendered",blue>20);b.recycle();
        });
        DmTileEngine broken=new DmTileEngine(raw,hash,name->new ByteArrayInputStream(new byte[]{1,2,3}));
        try{broken.retainJourney(broken.keys());fail("Accepted broken tile");}catch(IOException expected){}
    }
}
