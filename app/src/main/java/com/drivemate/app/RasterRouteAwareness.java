package com.drivemate.app;

import android.graphics.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;
import java.util.*;

/** Bounded, route-indexed raster fallback overlays. No new geometry or road attribution. */
final class RasterRouteAwareness extends Overlay {
    private final RouteRenderGeometry route;
    private final long fetchedAt;
    private final List<Traffic> traffic=new ArrayList<>();
    private final List<SpeedChangeAwareness.Change> limits=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private double along,first=Double.NaN,second=Double.NaN;
    private boolean metric;
    private static final class Traffic{
        final double start,end;final List<GeoMath.Point> vertices;
        Traffic(double a,double b,List<GeoMath.Point> v){start=a;end=b;vertices=v;}
    }
    RasterRouteAwareness(List<GeoMath.Point> path,List<RouteIntelligence.SpeedSection> speed,List<RouteIntelligence.TrafficSection> events,long fetchedAt,boolean metric){
        route=new RouteRenderGeometry(path);this.fetchedAt=fetchedAt;this.metric=metric;
        if(speed!=null)for(RouteIntelligence.SpeedSection s:speed){
            if(limits.size()>=200)break;
            if(s.end-s.start<8||s.end>route.length)continue;
            SpeedChangeAwareness.Change change=SpeedChangeAwareness.next(speed,s,s.end-4,0);
            if(change!=null)limits.add(change);
        }
        if(events!=null)for(RouteIntelligence.TrafficSection t:events){
            if(traffic.size()>=100)break;
            if(!Double.isFinite(t.start)||!Double.isFinite(t.end)||t.start<0||t.start>=route.length||t.end<=t.start)continue;
            List<GeoMath.Point> vertices=GeoMath.slice(path,t.start,Math.min(route.length,t.end));
            if(vertices.size()>1&&vertices.size()<=4000)traffic.add(new Traffic(t.start,t.end,vertices));
        }
    }
    void update(double along,double first,double second,boolean metric){this.along=Math.max(0,along);this.first=first;this.second=second;this.metric=metric;}
    void setMetric(boolean metric){this.metric=metric;}
    private Point pixel(Projection p,GeoMath.Point g){return p.toPixels(new GeoPoint(g.lat,g.lon),null);}
    @Override public void draw(Canvas canvas,MapView map,boolean shadow){
        if(shadow||route.length<=0||map.getZoomLevelDouble()<13)return;
        Projection projection=map.getProjection();float density=map.getResources().getDisplayMetrics().density;
        long age=System.currentTimeMillis()-fetchedAt;
        if(age>=0&&age<=480000){
            int count=0,vertices=0;
            for(Traffic t:traffic){
                if(t.end<along||t.start-along>5000||count>=20||vertices+t.vertices.size()>5000)continue;
                Path path=new Path();boolean start=true;
                for(GeoMath.Point g:t.vertices){Point p=pixel(projection,g);if(start){path.moveTo(p.x,p.y);start=false;}else path.lineTo(p.x,p.y);}
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setColor(0xffd97719);
                paint.setPathEffect(new DashPathEffect(new float[]{5*density,5*density},0));canvas.drawPath(path,paint);paint.setPathEffect(null);
                count++;vertices+=t.vertices.size();
            }
            if(map.getZoomLevelDouble()>=15)for(SpeedChangeAwareness.Change c:limits){
                if(c.offset<along||c.offset-along>2500)continue;
                GeoMath.Point g=route.at(c.offset);if(g==null)continue;Point p=pixel(projection,g);
                float r=11*density;paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawCircle(p.x,p.y,r,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f*density);paint.setColor(0xffd9272e);canvas.drawCircle(p.x,p.y,r,paint);
                paint.setStyle(Paint.Style.FILL);paint.setColor(Color.BLACK);paint.setTextSize(10*density);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.CENTER);
                canvas.save();canvas.rotate(-map.getMapOrientation(),p.x,p.y);
                canvas.drawText(String.valueOf(metric?c.kmh:Math.round(c.kmh/1.609344)),p.x,p.y-(paint.ascent()+paint.descent())/2,paint);canvas.restore();
            }
        }
        for(double offset:new double[]{first,second}){
            if(!Double.isFinite(offset)||offset<along||offset+5>route.length)continue;
            GeoMath.Point g=route.at(offset),h=route.at(Math.min(route.length,offset+12));if(g==null||h==null)continue;
            Point p=pixel(projection,g),q=pixel(projection,h);double length=Math.hypot(q.x-p.x,q.y-p.y);if(length<1)continue;
            float dx=(float)((q.x-p.x)/length),dy=(float)((q.y-p.y)/length),size=10*density;
            Path arrow=new Path();arrow.moveTo(p.x-dx*size,p.y-dy*size);arrow.lineTo(p.x+dx*size,p.y+dy*size);
            arrow.moveTo(p.x-dy*size*.8f,p.y+dx*size*.8f);arrow.lineTo(p.x+dx*size,p.y+dy*size);arrow.lineTo(p.x+dy*size*.8f,p.y-dx*size*.8f);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(7*density);paint.setColor(0xff154b78);canvas.drawPath(arrow,paint);
            paint.setStrokeWidth(3.5f*density);paint.setColor(Color.WHITE);canvas.drawPath(arrow,paint);
        }
    }
}
