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
            DmTileEngine engine=new DmTileEngine(manifest,expected,name->assets.open("dmtiles/"+name));
            engine.retainJourney(engine.keys());
            DmTileEngine.Route r=engine.route(new GeoMath.Point(51.209,-2.647),new GeoMath.Point(51.207,-2.641));
            List<JSONArray> features=engine.features(engine.keys());
            a.runOnUiThread(()->{if(a.isFinishing())return;LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
                TextView label=new TextView(a);label.setPadding(16,8,16,8);label.setText(String.format(Locale.UK,"Wells pilot · %.2f km · %d ms · %d tiles\nLocal calculation, not for driving.",r.metres/1000,r.ms,engine.cachedTiles()));body.addView(label);
                DmPilotMapView view=new DmPilotMapView(a,features,r.points);body.addView(view,new LinearLayout.LayoutParams(-1,Math.round(360*a.getResources().getDisplayMetrics().density)));
                new AlertDialog.Builder(a).setTitle("DriveMate original stack").setView(body).setPositiveButton("Close",null).show();});
        }catch(Exception e){a.runOnUiThread(()->{if(!a.isFinishing())new AlertDialog.Builder(a).setTitle("DriveMate pilot unavailable").setMessage(e.getMessage()).setPositiveButton("Close",null).show();});}},"dmtiles-pilot").start();
    }
}
