package com.drivemate.app;
import android.app.*;
import android.content.*;
import android.widget.*;
import android.view.*;
import java.util.*;
import org.json.*;
/** Isolated engineering demonstration of original map, routing and rendering. */
public final class DmPilotScreen{
    static void show(Activity a){
        new Thread(()->{try{
            android.content.res.AssetManager assets=a.getAssets();
            byte[] manifest=DmTileEngine.read(assets.open("dmtiles/manifest.json"),2_000_000);
            String expected=new String(DmTileEngine.read(assets.open("dmtiles/manifest.sha256"),128),java.nio.charset.StandardCharsets.UTF_8).trim();
            DmTileDiskSource cache=new DmTileDiskSource(new java.io.File(a.getFilesDir(),"dmtiles-cache"),manifest,expected,8_000_000,name->assets.open("dmtiles/"+name+".bin"));
            DmTileEngine engine=new DmTileEngine(manifest,expected,cache);
            DmTileEngine.Route r=engine.route(new GeoMath.Point(51.209,-2.647),new GeoMath.Point(51.207,-2.641));
            DmCellPlanner.Coverage coverage=engine.coverage(r,48.3,8_000_000);cache.prefetch(coverage.cells);
            List<JSONArray> features=engine.features(engine.keys());
            Map<Long,JSONObject> tags=new HashMap<>();for(JSONArray row:features)tags.put(row.getLong(0),row.getJSONObject(1));
            List<DmGuidance.Step> steps=DmGuidance.preview(r.points,r.ways,tags);
            a.runOnUiThread(()->{if(a.isFinishing())return;LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
                TextView label=new TextView(a);label.setPadding(16,8,16,8);label.setText(String.format(Locale.UK,"Wells pilot · %.2f km · %d ms · %d tiles\nLocal calculation, not for driving.",r.metres/1000,r.ms,engine.cachedTiles()));body.addView(label);
                DmPilotMapView view=new DmPilotMapView(a,features,r.points);body.addView(view,new LinearLayout.LayoutParams(-1,Math.round(300*a.getResources().getDisplayMetrics().density)));
                Button tilt=new Button(a);tilt.setText("Toggle perspective preview");tilt.setOnClickListener(v->view.togglePerspective());body.addView(tilt);
                TextView guidance=new TextView(a);StringBuilder detail=new StringBuilder("Candidate instructions (unverified)\n");for(DmGuidance.Step step:steps){if(detail.length()>500)break;detail.append(Math.round(step.metres)).append(" m · ").append(step.instruction).append("\n");}guidance.setText(detail.toString());body.addView(guidance);
                new AlertDialog.Builder(a).setTitle("DriveMate original stack").setView(body).setPositiveButton("Close",null).show();});
        }catch(Exception e){a.runOnUiThread(()->{if(!a.isFinishing())new AlertDialog.Builder(a).setTitle("DriveMate pilot unavailable").setMessage(e.getMessage()).setPositiveButton("Close",null).show();});}},"dmtiles-pilot").start();
    }
}
