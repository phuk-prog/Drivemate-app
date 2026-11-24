package com.drivemate.app;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.*;
import java.util.*;
/** Original Canvas renderer prototype, north-up road geometry, no driving use. */
public final class DmPilotMapView extends View{
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<JSONArray> features;
    private List<GeoMath.Point> route;
    public void updateRoute(List<GeoMath.Point> next){route=next;for(GeoMath.Point p:next)include(p.lat,p.lon);invalidate();}
    private boolean perspective;
    public void togglePerspective(){perspective=!perspective;invalidate();}
    private double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
    public DmPilotMapView(Context c,List<JSONArray> f,List<GeoMath.Point> r){super(c);features=f;route=r;
        for(JSONArray row:f){JSONArray coords=row.optJSONArray(2);if(coords!=null)for(int i=0;i<coords.length();i++){JSONArray p=coords.optJSONArray(i);if(p!=null)include(p.optDouble(0)/1e6,p.optDouble(1)/1e6);}}
        for(GeoMath.Point p:r)include(p.lat,p.lon);setContentDescription("Experimental DriveMate original map renderer, not for driving");}
    private void include(double lat,double lon){minLat=Math.min(lat,minLat);maxLat=Math.max(lat,maxLat);minLon=Math.min(lon,minLon);maxLon=Math.max(lon,maxLon);}
    private float x(double lon){return 12+(float)((lon-minLon)/Math.max(.000001,maxLon-minLon)*(getWidth()-24));}
    private float y(double lat){return 12+(float)((maxLat-lat)/Math.max(.000001,maxLat-minLat)*(getHeight()-24));}
    private float projectY(double lat){float y=y(lat);if(!perspective)return y;double t=y/Math.max(1,getHeight());return (float)(getHeight()*(.19+.78*t*t));}
    private float projectX(double lat,double lon){float x=x(lon);if(!perspective)return x;double t=y(lat)/Math.max(1,getHeight());return (float)(getWidth()/2.0+(x-getWidth()/2.0)*(.48+.52*t));}
    @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Color.rgb(242,244,236));paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        for(JSONArray row:features){JSONObject tags=row.optJSONObject(1);JSONArray pts=row.optJSONArray(2);if(tags==null||pts==null||pts.length()<2)continue;
            boolean road=tags.has("highway"),water=tags.has("waterway"),building=tags.has("building");if(!road&&!water&&!building)continue;
            Path path=new Path();for(int i=0;i<pts.length();i++){JSONArray p=pts.optJSONArray(i);if(p==null)continue;float x=projectX(p.optDouble(0)/1e6,p.optDouble(1)/1e6),y=projectY(p.optDouble(0)/1e6);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}
            paint.setColor(road?Color.rgb(156,159,157):water?Color.rgb(104,170,210):Color.rgb(188,180,164));paint.setStrokeWidth(road?2.5f:1.5f);c.drawPath(path,paint);
        }
        if(route.size()>1){Path path=new Path();for(int i=0;i<route.size();i++){GeoMath.Point p=route.get(i);if(i==0)path.moveTo(projectX(p.lat,p.lon),projectY(p.lat));else path.lineTo(projectX(p.lat,p.lon),projectY(p.lat));}paint.setColor(Color.rgb(33,105,220));paint.setStrokeWidth(6);c.drawPath(path,paint);}
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.DKGRAY);paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);c.drawText("© OpenStreetMap contributors · PILOT",12,getHeight()-8,paint);
    }
}
