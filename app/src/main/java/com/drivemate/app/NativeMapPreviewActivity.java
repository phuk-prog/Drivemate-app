package com.drivemate.app;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;

/** In-app original renderer engineering preview; never starts driving. */
public final class NativeMapPreviewActivity extends Activity {
    NativeMapView map;TextView status;NativeMapScene scene;boolean night,tilted;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff0a273b);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setPadding(16,14,16,14);status.setText("Loading DriveMate original renderer · not for driving");root.addView(status);
        map=new NativeMapView(this);root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(this);
        for(String title:new String[]{"−","+","Day/night","Tilt","Close"}){
            Button button=new Button(this);button.setText(title);controls.addView(button,new LinearLayout.LayoutParams(0,-2,1));
            button.setOnClickListener(v->{switch(title){case "−":map.setZoom(map.zoom()-1);break;case "+":map.setZoom(map.zoom()+1);break;case "Day/night":night=!night;map.setNight(night);break;case "Tilt":tilted=!tilted;position(map.zoom(),night,tilted);break;default:finish();}});
        }
        root.addView(controls);setContentView(root);
        new Thread(()->{try{
            DmUnifiedRegion region=new DmUnifiedRegion(this,true);DmTileEngine.Route route=region.sample(true);
            NativeMapScene loaded=region.nativeScene(route);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;scene=loaded;map.setScene(loaded);status.setText("DriveMate original renderer · "+loaded.checkedEdges+" exact road edges · preview only");});
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("Native rendering unavailable: "+e.getMessage());});}},"dm-native-render-load").start();
    }
    void position(double zoom,boolean night,boolean tilt){
        if(scene==null)return;RouteRenderGeometry r=new RouteRenderGeometry(scene.route);double d=r.length*.4;
        GeoMath.Point center=r.at(d);double heading=GeoMath.bearingDegrees(r.at(Math.max(0,d-12)),r.at(Math.min(r.length,d+12)));
        map.setNight(night);map.setCamera(center,zoom,tilt?heading:0,tilt?45:0);
        map.setProgress(d,center,Math.min(r.length-1,d+60));
    }
}
