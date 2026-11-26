package com.drivemate.app;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** DriveMate-owned Canvas display library: projection, culling, layers, labels, route and camera.
 * Android Canvas supplies platform drawing primitives; no external map SDK is used here. */
final class NativeMapView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final float[] screen=new float[2];
    private final ArrayList<RectF> occupied=new ArrayList<>();
    private NativeMapScene scene;
    private GeoMath.Point center,vehicle;
    private RouteRenderGeometry route;
    private double zoom=16,bearing=0,tilt=0,progress=0,turn=Double.NaN;
    private boolean night;
    private int roadsDrawn,routeDrawn,buildingsDrawn;
    private float lastX,lastY;
    private final ScaleGestureDetector scale;
    NativeMapView(Context context){
        super(context);setContentDescription("DriveMate original map rendering preview; not for driving");
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){setZoom(zoom+Math.log(d.getScaleFactor())/Math.log(2));return true;}
        });
    }
    void setScene(NativeMapScene value){scene=value;route=new RouteRenderGeometry(value.route);center=route.at(route.length*.4);vehicle=center;invalidate();}
    void setZoom(double value){zoom=Math.max(11,Math.min(21,value));invalidate();}
    double zoom(){return zoom;}
    void setNight(boolean value){night=value;invalidate();}
    void setCamera(GeoMath.Point p,double z,double bearing,double tilt){if(p==null)return;center=p;zoom=Math.max(11,Math.min(21,z));this.bearing=bearing;this.tilt=tilt;invalidate();}
    void setProgress(double value,GeoMath.Point vehicle,double turnOffset){progress=Math.max(0,value);this.vehicle=vehicle;turn=turnOffset;invalidate();}
    int roadsDrawn(){return roadsDrawn;}int routeDrawn(){return routeDrawn;}int buildingsDrawn(){return buildingsDrawn;}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private int color(int day,int dark){return night?dark:day;}
    private NativeMapProjection.Camera camera(){return new NativeMapProjection.Camera(center.lat,center.lon,zoom,bearing,tilt,getWidth(),getHeight());}
    private void point(NativeMapProjection.Camera c,GeoMath.Point p){c.screen(NativeMapProjection.x(p.lon),NativeMapProjection.y(p.lat),screen);}
    private boolean visible(NativeMapScene.Shape f,NativeMapProjection.Camera c){
        float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
        for(double x:new double[]{f.w,f.e})for(double y:new double[]{f.s,f.n}){c.screen(x,y,screen);minX=Math.min(minX,screen[0]);maxX=Math.max(maxX,screen[0]);minY=Math.min(minY,screen[1]);maxY=Math.max(maxY,screen[1]);}
        return maxX>=-30&&minX<=getWidth()+30&&maxY>=-30&&minY<=getHeight()+30;
    }
    private void makePath(NativeMapScene.Shape f,NativeMapProjection.Camera c){
        path.reset();for(int i=0;i<f.xy.length;i+=2){c.screen(f.xy[i],f.xy[i+1],screen);if(i==0)path.moveTo(screen[0],screen[1]);else path.lineTo(screen[0],screen[1]);}if(f.area)path.close();
    }
    private void stroke(Canvas c,int color,float width){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(width));c.drawPath(path,paint);}
    private void fill(Canvas c,int color){paint.setColor(color);paint.setStyle(Paint.Style.FILL);c.drawPath(path,paint);}
    private void routePath(Canvas canvas,NativeMapProjection.Camera c,List<GeoMath.Point> points,int color,float width){
        if(points.size()<2)return;path.reset();boolean first=true;for(GeoMath.Point p:points){point(c,p);if(first){path.moveTo(screen[0],screen[1]);first=false;}else path.lineTo(screen[0],screen[1]);}
        stroke(canvas,color,width);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(color(0xffeef1e9,0xff132634));roadsDrawn=routeDrawn=buildingsDrawn=0;
        if(scene==null||center==null||getWidth()==0)return;
        NativeMapProjection.Camera c=camera();paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);occupied.clear();
        ArrayList<NativeMapScene.Shape> visible=new ArrayList<>();
        for(NativeMapScene.Shape f:scene.shapes){if(f.kind==NativeMapScene.BUILDING&&zoom<15||!visible(f,c))continue;visible.add(f);}
        for(NativeMapScene.Shape f:visible){
            if(!f.area)continue;makePath(f,c);
            if(f.kind==NativeMapScene.BUILDING){fill(canvas,color(0xffd7cbb9,0xff334654));stroke(canvas,color(0xffc2b6a3,0xff455867),.7f);buildingsDrawn++;}
            else if(f.kind==NativeMapScene.WATER)fill(canvas,color(0xffa6d5e9,0xff254d6b));
            else fill(canvas,color(0xffdce9ce,0xff243c37));
        }
        for(int pass=0;pass<2;pass++)for(NativeMapScene.Shape f:visible){
            if(f.area||f.bridge!=(pass==1))continue;makePath(f,c);
            if(f.kind==NativeMapScene.WATER){stroke(canvas,color(0xff8bc6e2,0xff376989),2);continue;}
            if(f.kind==NativeMapScene.RAIL){stroke(canvas,color(0xff969c9e,0xff687782),1);continue;}
            if(f.kind<NativeMapScene.PATH)continue;
            float width=f.kind==NativeMapScene.MOTORWAY?7:f.kind==NativeMapScene.MAIN?5:f.kind==NativeMapScene.LOCAL?3.4f:1.2f;
            width*=Math.max(.7f,Math.min(1.6f,(float)(1+(zoom-16)*.12)));
            stroke(canvas,color(0xffb1b8b9,0xff10202d),width+1.7f);
            stroke(canvas,f.tunnel?color(0xffd6d8d6,0xff465461):f.kind==NativeMapScene.MOTORWAY?color(0xfff1cd9d,0xffa18b68):f.kind==NativeMapScene.MAIN?color(0xffffe7a7,0xff667178):color(0xffffffff,0xff50616d),width);
            roadsDrawn++;
        }
        if(zoom>=15){paint.setTextSize(dp(11));paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setStyle(Paint.Style.FILL);
            for(NativeMapScene.Shape f:visible){
                if(f.name.isEmpty()||f.kind<NativeMapScene.LOCAL||f.kind>NativeMapScene.MOTORWAY||occupied.size()>=160)continue;
                int i=(f.xy.length/4)*2;c.screen(f.xy[i],f.xy[i+1],screen);float x=screen[0],y=screen[1],w=paint.measureText(f.name);
                RectF box=new RectF(x-w/2-dp(3),y-dp(12),x+w/2+dp(3),y+dp(3));if(box.left<0||box.right>getWidth()||box.top<0||box.bottom>getHeight())continue;
                boolean collision=false;for(RectF other:occupied)if(RectF.intersects(other,box)){collision=true;break;}if(collision)continue;
                occupied.add(box);paint.setColor(color(0xfff8faf7,0xff142737));canvas.drawRoundRect(box,dp(3),dp(3),paint);
                paint.setColor(color(0xff344451,0xffe4edf3));canvas.drawText(f.name,x-w/2,y,paint);
            }
        }
        if(route!=null){routePath(canvas,c,route.full(),color(0xffaeb9bb,0xff516270),6);routePath(canvas,c,route.remaining(progress),0xff143d64,8);routePath(canvas,c,route.remaining(progress),0xff16a6f4,5);
            if(route.full().size()>1)routeDrawn=1;
            if(Double.isFinite(turn)&&turn>progress&&turn<route.length){
                GeoMath.Point p=route.at(turn),q=route.at(Math.min(route.length,turn+18));point(c,p);float ax=screen[0],ay=screen[1];point(c,q);
                float dx=screen[0]-ax,dy=screen[1]-ay,len=(float)Math.hypot(dx,dy);if(len>1){dx/=len;dy/=len;float size=dp(11);path.reset();path.moveTo(ax+dx*size,ay+dy*size);path.lineTo(ax-dx*size*.6f-dy*size*.65f,ay-dy*size*.6f+dx*size*.65f);path.lineTo(ax-dx*size*.6f+dy*size*.65f,ay-dy*size*.6f-dx*size*.65f);path.close();fill(canvas,Color.WHITE);}
            }
            point(c,route.full().get(route.full().size()-1));paint.setStyle(Paint.Style.FILL);paint.setColor(0xffc33538);canvas.drawCircle(screen[0],screen[1],dp(6),paint);
        }
        if(vehicle!=null){point(c,vehicle);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawCircle(screen[0],screen[1],dp(10),paint);paint.setColor(0xff007ac9);canvas.drawCircle(screen[0],screen[1],dp(7),paint);}
        paint.setStyle(Paint.Style.FILL);paint.setTextSize(dp(10));paint.setColor(color(0xff344451,0xfff1f4f5));canvas.drawText("© OpenStreetMap contributors · DriveMate renderer preview",dp(8),getHeight()-dp(8),paint);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        scale.onTouchEvent(e);if(center==null)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float x=e.getX(),y=e.getY();if(!scale.isInProgress()&&e.getPointerCount()==1){NativeMapProjection.Camera c=camera();center=c.unproject(c.anchorX+lastX-x,c.anchorY+lastY-y);invalidate();}lastX=x;lastY=y;return true;}
        return true;
    }
}
