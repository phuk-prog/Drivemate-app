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
/** Original engine and renderer replay: not production navigation or live voice. */
@RunWith(AndroidJUnit4.class)
public class DmComparisonLabTest {
    @Test public void replayCommonTrace()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test=InstrumentationRegistry.getInstrumentation().getContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        JSONObject trace=new JSONObject(new String(DmTileEngine.read(test.getAssets().open("navigation-lab.json"),2000000)));
        byte[] raw=DmTileEngine.read(app.getAssets().open("dmtiles/manifest.json"),2000000);
        String hash=new String(DmTileEngine.read(app.getAssets().open("dmtiles/manifest.sha256"),128)).trim();
        File root=new File(app.getCacheDir(),"lab-"+System.nanoTime());assertTrue(root.mkdirs());
        File out=new File(app.getExternalFilesDir(null),"navigation-lab");assertTrue(out.isDirectory()||out.mkdirs());
        DmTileDiskSource disk=new DmTileDiskSource(root,raw,hash,8000000,name->app.getAssets().open("dmtiles/"+name+".bin"));
        DmTileEngine engine=new DmTileEngine(raw,hash,disk);disk.prefetch(engine.keys());
        DmNavigationSession session=new DmNavigationSession(engine,disk,new File(root,"session.json"),hash);
        JSONArray a=trace.getJSONArray("start"),b=trace.getJSONArray("finish");
        session.plan(new GeoMath.Point(a.getDouble(0),a.getDouble(1)),new GeoMath.Point(b.getDouble(0),b.getDouble(1)),false,0,1);
        List<JSONArray> features=engine.features(engine.keys());
        JSONArray fixes=trace.getJSONArray("points"),results=new JSONArray();
        for(int i=0;i<fixes.length();i++){
            JSONObject fix=fixes.getJSONObject(i);
            GeoMath.Point gps=new GeoMath.Point(fix.getDouble("lat"),fix.getDouble("lon"));
            DmNavigationSession.Update u=session.fix(gps,5,1000L+i*1000L,false);
            results.put(new JSONObject().put("t",i).put("status",u.status.name()).put("remaining",Double.isFinite(u.remaining)?u.remaining:JSONObject.NULL).put("event",fix.getString("event")));
            if(i%20==0||i==fixes.length()-1){
                final int frame=i; final DmNavigationSession.Update update=u;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                    DmPilotMapView view=new DmPilotMapView(app,features,update.route.points);
                    view.layout(0,0,540,760);Bitmap bitmap=Bitmap.createBitmap(540,800,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(bitmap);view.draw(canvas);Paint paint=new Paint();paint.setColor(Color.DKGRAY);paint.setTextSize(15);
                    canvas.drawText("ENGINE PREVIEW | "+update.status+" | t="+frame,12,785,paint);
                    try(FileOutputStream file=new FileOutputStream(new File(out,String.format(Locale.ROOT,"frame-%04d.png",frame)))){bitmap.compress(Bitmap.CompressFormat.PNG,100,file);}
                    catch(IOException e){throw new RuntimeException(e);}finally{bitmap.recycle();}
                });
            }
        }
        try(FileOutputStream file=new FileOutputStream(new File(out,"drivemate-engine-results.json"))){file.write(results.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertEquals(fixes.length(),results.length());
        android.util.Log.i("NavigationLab","LAB_DRIVEMATE "+fixes.length()+" fixes; renderer preview captured; no live voice");
    }
}
