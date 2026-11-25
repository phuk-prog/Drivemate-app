package com.drivemate.app;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.Gravity;
import android.widget.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.*;
import org.maplibre.android.camera.*;
import org.maplibre.android.geometry.*;
import org.maplibre.android.style.sources.GeoJsonSource;

/** High-detail GPU regional map and route, one source geometry at every zoom. */
public final class DmUnifiedPreviewActivity extends Activity {
    private MapView view;private MapLibreMap map;private TextView status;
    private DmUnifiedGeometry geometry;private DmTileEngine.Route route;
    private boolean night,perspective=true,stockport,ready;private int generation;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);stockport=getIntent().getBooleanExtra("stockport",true);
        MapLibre.getInstance(getApplicationContext());
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setBackgroundColor(0xff0a273b);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(16);status.setPadding(18,14,18,14);
        status.setText("Loading shared roads · preview only");body.addView(status);
        view=new MapView(this);view.onCreate(state);body.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        TextView attribution=new TextView(this);attribution.setTextColor(Color.WHITE);attribution.setGravity(Gravity.CENTER);attribution.setText("© OpenStreetMap contributors · ODbL · Not for driving");body.addView(attribution);
        LinearLayout controls=new LinearLayout(this);
        for(String label:new String[]{"Day / night","2D / 3D","Close"}){
            Button b=new Button(this);b.setText(label);controls.addView(b,new LinearLayout.LayoutParams(0,-2,1));
            b.setOnClickListener(v->{if(label.equals("Close"))finish();else if(label.equals("Day / night")){night=!night;render();}else{perspective=!perspective;position(15.8);}});
        }
        body.addView(controls);setContentView(body);
        view.getMapAsync(m->{if(isDestroyed())return;map=m;map.setMinZoomPreference(11);map.setMaxZoomPreference(21);render();});
        new Thread(()->{try{
            DmUnifiedRegion region=new DmUnifiedRegion(this,stockport);DmTileEngine.Route result=region.sample(stockport);
            DmUnifiedGeometry verified=new DmUnifiedGeometry(region.features,result);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;route=result;geometry=verified;
                status.setText((stockport?"Stockport":"Wells")+" · "+Math.round(route.metres)+" m · "+verified.checkedEdges+" shared edges\nFull source geometry · preview only");render();});
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("Preview unavailable: "+e.getMessage());});}},"dm-unified-region").start();
    }
    private void render(){
        if(map==null||geometry==null||isDestroyed())return;ready=false;final int gen=++generation;
        try{
            String json=new String(DmTileEngine.read(getAssets().open(night?"dm_unified_night.json":"dm_unified_day.json"),500_000),java.nio.charset.StandardCharsets.UTF_8);
            map.setStyle(new Style.Builder().fromJson(json),s->{
                if(isDestroyed()||gen!=generation)return;
                GeoJsonSource roads=s.getSourceAs("unified-roads"),areas=s.getSourceAs("unified-areas"),line=s.getSourceAs("unified-route");
                if(roads==null||areas==null||line==null){status.setText("Map sources unavailable");return;}
                roads.setGeoJson(geometry.roads);areas.setGeoJson(geometry.areas);line.setGeoJson(geometry.route);
                ready=true;view.post(()->position(15.8));
            });
        }catch(Exception e){status.setText("Style unavailable: "+e.getMessage());}
    }
    void position(double zoom){
        if(!ready||map==null||view.getWidth()<1)return;
        double distance=GeoMath.length(geometry.points)*.4;GeoMath.Point center=GeoMath.pointAtDistance(geometry.points,distance);
        GeoMath.Point a=GeoMath.pointAtDistance(geometry.points,Math.max(0,distance-12)),b=GeoMath.pointAtDistance(geometry.points,distance+12);
        map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(center.lat,center.lon)).zoom(zoom)
            .bearing(perspective?GeoMath.bearingDegrees(a,b):0).tilt(perspective?48:0).build()));
    }
    boolean rendered(double zoom){
        if(!ready||map==null||view.getWidth()<100||view.getHeight()<100||Math.abs(map.getCameraPosition().zoom-zoom)>.2)return false;
        RectF r=new RectF(0,0,view.getWidth(),view.getHeight());
        try{return !map.queryRenderedFeatures(r,"road-fill").isEmpty()&&!map.queryRenderedFeatures(r,"route-fill").isEmpty();}catch(RuntimeException e){return false;}
    }
    int checkedEdges(){return geometry==null?0:geometry.checkedEdges;}
    @Override public void onStart(){super.onStart();view.onStart();}
    @Override public void onResume(){super.onResume();view.onResume();}
    @Override public void onPause(){view.onPause();super.onPause();}
    @Override public void onStop(){view.onStop();super.onStop();}
    @Override public void onLowMemory(){super.onLowMemory();view.onLowMemory();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);view.onSaveInstanceState(out);}
    @Override public void onDestroy(){generation++;view.onDestroy();super.onDestroy();}
}
