package com.drivemate.app;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.location.*;
import android.net.ConnectivityManager;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
/** Test-only injected road fixture; exercises actual MainActivity guidance UI, not the online provider. */
@RunWith(AndroidJUnit4.class)
public class DmDrivingUiLabTest {
    static Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    static void call(MainActivity a,String name,Class<?>[] types,Object...args)throws Exception{
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(a,args);
    }
    static String text(MainActivity a,String name)throws Exception{
        Object v=field(name).get(a);return v instanceof TextView?((TextView)v).getText().toString():String.valueOf(v);
    }
    static void onMain(Instrumentation i,Work w)throws Exception{
        final Exception[] error={null};i.runOnMainSync(()->{try{w.run();}catch(Exception e){error[0]=e;}});if(error[0]!=null)throw error[0];
    }
    interface Work{void run()throws Exception;}
    static Location fix(JSONObject p)throws Exception{
        Location loc=new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(p.getDouble("lat"));loc.setLongitude(p.getDouble("lon"));loc.setAccuracy(5);loc.setSpeed((float)p.getDouble("mps"));
        loc.setTime(System.currentTimeMillis());loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());return loc;
    }
    @Test public void replayActualDrivingChrome()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertNull(((ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork());
        JSONObject trace=new JSONObject(new String(DmTileEngine.read(ins.getContext().getAssets().open("navigation-lab.json"),2000000)));
        JSONArray geometry=trace.getJSONArray("route_geometry"),fixes=trace.getJSONArray("points");
        List<GeoMath.Point> road=new ArrayList<>();
        for(int i=0;i<geometry.length();i++){JSONArray p=geometry.getJSONArray(i);road.add(new GeoMath.Point(p.getDouble(0),p.getDouble(1)));}
        double length=GeoMath.length(road);List<TomTomApi.Instruction> turns=new ArrayList<>();JSONArray candidates=trace.getJSONArray("turn_candidates");
        for(int i=0;i<candidates.length();i++){JSONObject j=candidates.getJSONObject(i);double angle=j.getDouble("angle");boolean left=angle<0;
            turns.add(new TomTomApi.Instruction(j.getDouble("metres"),left?"Turn left":"Turn right",left?"TURN_LEFT":"TURN_RIGHT","REGULAR",0,angle,"",""));}
        turns.add(new TomTomApi.Instruction(length,"Destination ahead","ARRIVE","REGULAR",0,0,"",""));
        TomTomApi.Route route=new TomTomApi.Route(road,turns,length,Math.max(1,(int)(length/8)),0,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),"Navigation Lab fixture");
        Set<Integer> captures=new HashSet<>();JSONArray checkpoints=trace.getJSONArray("capture_times");for(int i=0;i<checkpoints.length();i++)captures.add(checkpoints.getInt(i));
        File out=new File(app.getExternalFilesDir(null),"navigation-ui-lab");assertTrue(out.isDirectory()||out.mkdirs());
        Intent launch=new Intent(app,MainActivity.class);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity=(MainActivity)ins.startActivitySync(launch);
        JSONArray results=new JSONArray();
        try{
            ins.waitForIdleSync();SystemClock.sleep(5000);
            onMain(ins,()->{
                Location loc=fix(fixes.getJSONObject(0));field("currentLocation").set(activity,loc);
                field("destination").set(activity,new TomTomApi.Destination("Navigation Lab road endpoint","Wells QA only",road.get(road.size()-1)));
                field("lastRouteRequest").setLong(activity,SystemClock.elapsedRealtime());
                field("voiceMode").setInt(activity,DrivingVoicePolicy.NORMAL);
                call(activity,"installCalculatedRoute",new Class<?>[]{TomTomApi.Route.class,boolean.class},route,true);
                call(activity,"startNavigation",new Class<?>[]{});
                LocationManager lm=(LocationManager)field("locationManager").get(activity);if(lm!=null)lm.removeUpdates(activity);
                assertTrue(field("active").getBoolean(activity));
            });
            final int[] silentConfirmed={0};
            long began=SystemClock.elapsedRealtime();
            for(int k=0;k<fixes.length();k++){
                final int index=k;JSONObject point=fixes.getJSONObject(k);
                long wait=began+1000L*k-SystemClock.elapsedRealtime();if(wait>0)SystemClock.sleep(wait);
                onMain(ins,()->{
                    if(index==fixes.length()/3)call(activity,"setVoiceMode",new Class<?>[]{int.class},DrivingVoicePolicy.SILENT);
                    if(index==fixes.length()*2/3)call(activity,"setVoiceMode",new Class<?>[]{int.class},DrivingVoicePolicy.NORMAL);
                    Location loc=fix(point);field("currentLocation").set(activity,loc);
                    // Invoke actual GPS callback, with no mock fix sent to other apps.
                    activity.onLocationChanged(loc);
                    if(field("voiceMode").getInt(activity)==DrivingVoicePolicy.SILENT&&field("active").getBoolean(activity)
                            &&field("guidancePositionConfirmed").getBoolean(activity)){
                        assertFalse(text(activity,"tripDistanceValue").equals("—"));
                        assertFalse(text(activity,"topInstructionText").isEmpty());
                        silentConfirmed[0]++;
                    }
                    results.put(new JSONObject().put("t",index).put("active",field("active").getBoolean(activity))
                        .put("heading",text(activity,"topTurnTitle")).put("instruction",text(activity,"topInstructionText"))
                        .put("next_instruction",text(activity,"nextInstruction")).put("eta",text(activity,"tripEtaValue"))
                        .put("speech_ready",field("speechReady").getBoolean(activity)));
                });
                if(captures.contains(k)){
                    Bitmap shot=ins.getUiAutomation().takeScreenshot();
                    if(shot!=null){try(FileOutputStream file=new FileOutputStream(new File(out,String.format(Locale.ROOT,"frame-%04d.png",k)))){shot.compress(Bitmap.CompressFormat.PNG,100,file);}finally{shot.recycle();}}
                }
            }
            try(FileOutputStream file=new FileOutputStream(new File(out,"driving-ui-results.json"))){file.write(results.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            assertEquals(fixes.length(),results.length());
            assertTrue("Fixture must exercise confirmed visual guidance in silent mode",silentConfirmed[0]>0);
            android.util.Log.i("NavigationLab","LAB_DRIVING_UI "+results.length()+" actual UI fixes; fixture maneuvers not road validated; audio recording separate");
        }finally{onMain(ins,()->activity.finish());}
    }
}
