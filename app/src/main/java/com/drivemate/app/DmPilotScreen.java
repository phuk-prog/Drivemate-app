package com.drivemate.app;
import android.app.*;
import android.widget.*;
import android.view.*;
import android.speech.tts.TextToSpeech;
import java.util.*;
import java.io.File;
import org.json.*;
/** Integrated replay mode. It deliberately does not consume real GPS or speak road instructions. */
public final class DmPilotScreen{
    static void show(Activity a){
        new Thread(()->{try{
            android.content.res.AssetManager assets=a.getAssets();
            byte[] manifest=DmTileEngine.read(assets.open("dmtiles/manifest.json"),2_000_000);
            String expected=new String(DmTileEngine.read(assets.open("dmtiles/manifest.sha256"),128),java.nio.charset.StandardCharsets.UTF_8).trim();
            DmTileDiskSource cache=new DmTileDiskSource(new File(a.getFilesDir(),"dmtiles-cache"),manifest,expected,8_000_000,name->assets.open("dmtiles/"+name+".bin"));
            DmTileEngine engine=new DmTileEngine(manifest,expected,cache);
            DmNavigationSession session=new DmNavigationSession(engine,cache,new File(a.getFilesDir(),"dm-replay.json"),expected);
            DmTileEngine.Route route=session.plan(new GeoMath.Point(51.209,-2.647),new GeoMath.Point(51.207,-2.641),true,48.3,8_000_000);
            List<JSONArray> features=engine.features(engine.keys());
            Map<Long,JSONObject> tags=new HashMap<>();for(JSONArray row:features)tags.put(row.getLong(0),row.getJSONObject(1));
            a.runOnUiThread(()->{if(a.isFinishing()||a.isDestroyed())return;
                LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(12,8,12,8);
                TextView status=new TextView(a);status.setText(String.format(Locale.UK,"Wells simulated journey · %.2f km · %d ms\nNot for driving",route.metres/1000,route.ms));body.addView(status);
                DmPilotMapView view=new DmPilotMapView(a,features,route.points);body.addView(view,new LinearLayout.LayoutParams(-1,Math.round(260*a.getResources().getDisplayMetrics().density)));
                TextView steps=new TextView(a);body.addView(steps);describe(steps,route,tags);
                Button tilt=new Button(a);tilt.setText("Toggle perspective");tilt.setOnClickListener(v->view.togglePerspective());body.addView(tilt);
                Button reroute=new Button(a);reroute.setText("Simulate offline diversion");body.addView(reroute);
                long[] clock={1000};reroute.setOnClickListener(v->{reroute.setEnabled(false);new Thread(()->{
                    try{GeoMath.Point dev=new GeoMath.Point(51.214,-2.646);DmNavigationSession.Update u=null;
                        for(int i=0;i<3;i++)u=session.fix(dev,5,clock[0]+=1000,false);
                        DmNavigationSession.Update result=u;a.runOnUiThread(()->{if(a.isFinishing()||a.isDestroyed())return;view.updateRoute(result.route.points);describe(steps,result.route,tags);status.setText("Simulated offline result: "+result.status+" · "+Math.round(result.route.metres)+" m");reroute.setEnabled(true);});
                    }catch(Exception e){a.runOnUiThread(()->{status.setText("Replay unavailable: "+e.getMessage());reroute.setEnabled(true);});}
                },"dm-replay").start();});
                Button voice=new Button(a);voice.setText("Test offline voice");voice.setEnabled(false);body.addView(voice);
                TextToSpeech[] tts={null};tts[0]=new TextToSpeech(a,s->a.runOnUiThread(()->{if(s==TextToSpeech.SUCCESS&&tts[0]!=null){boolean available=false;for(android.speech.tts.Voice candidate:tts[0].getVoices()==null?Collections.<android.speech.tts.Voice>emptySet():tts[0].getVoices()){if(!candidate.isNetworkConnectionRequired()&&Locale.UK.getLanguage().equals(candidate.getLocale().getLanguage())&&!candidate.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)){available=tts[0].setVoice(candidate)==TextToSpeech.SUCCESS;if(available)break;}}voice.setEnabled(available);}}));
                voice.setOnClickListener(v->{if(tts[0]!=null)tts[0].speak("DriveMate simulation voice test. Not for driving.",TextToSpeech.QUEUE_FLUSH,null,"dm-preview");});
                ScrollView scroll=new ScrollView(a);scroll.addView(body);
                AlertDialog dialog=new AlertDialog.Builder(a).setTitle("DriveMate 2.0 independent simulation").setView(scroll).setPositiveButton("Close",null).create();
                dialog.setOnDismissListener(v->{if(tts[0]!=null){tts[0].stop();tts[0].shutdown();tts[0]=null;}});dialog.show();
            });
        }catch(Exception e){a.runOnUiThread(()->{if(!a.isFinishing())new AlertDialog.Builder(a).setTitle("DriveMate pilot unavailable").setMessage(e.getMessage()).setPositiveButton("Close",null).show();});}},"dmtiles-pilot").start();
    }
    private static void describe(TextView view,DmTileEngine.Route route,Map<Long,JSONObject> tags){
        StringBuilder detail=new StringBuilder("Unverified manoeuvre preview\n");
        for(DmGuidance.Step s:DmGuidance.preview(route.points,route.ways,tags)){if(detail.length()>300)break;detail.append(Math.round(s.metres)).append(" m · ").append(s.instruction).append("\n");}view.setText(detail.toString());
    }
}
