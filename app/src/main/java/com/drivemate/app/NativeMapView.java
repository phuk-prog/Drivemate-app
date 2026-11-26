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
    private double zoom=16,bearing=0,tilt=0,progress=0,turn=Double.NaN,secondTurn=Double.NaN,vehicleBearing=Double.NaN;
    private GeoMath.Point destination,entrance,parked;
    private boolean showBuildings=true,metric;
    private List<RouteIntelligence.SpeedSection> speeds=Collections.emptyList();
    private List<LiveTrafficIncidents.Event> incidents=Collections.emptyList();
    private long incidentAt;
    private Runnable gesture,paintListener,cameraChanged;
    private List<int[]> roadside=Collections.emptyList();
    private final ArrayList<NativeMapScene.Shape> visibleShapes=new ArrayList<>();
    private final float[] corner=new float[2];
    private boolean extrude=true;
    private long frameCount,drawTotalNanos,drawMaxNanos;
    long frameCount(){return frameCount;}
    long meanDrawNanos(){return frameCount==0?0:drawTotalNanos/frameCount;}
    long maxDrawNanos(){return drawMaxNanos;}
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
    void setScene(NativeMapScene value){scene=value;route=new RouteRenderGeometry(value==null?Collections.emptyList():value.route);if(value!=null&&!value.route.isEmpty()){center=route.at(route.length*.4);vehicle=center;}invalidate();}
    void setSceneOnly(NativeMapScene value){scene=value;invalidate();}
    void setRoute(List<GeoMath.Point> value){route=new RouteRenderGeometry(value);progress=0;turn=secondTurn=Double.NaN;invalidate();}
    void setVehicle(GeoMath.Point p,double direction){vehicle=p;vehicleBearing=direction;invalidate();}
    void setMarkers(GeoMath.Point d,GeoMath.Point e,GeoMath.Point p){destination=d;entrance=e;parked=p;invalidate();}
    void setProgressOnly(double p){progress=Math.max(0,p);invalidate();}
    void setTurns(double a,double b){turn=a;secondTurn=b;invalidate();}
    void setEvents(List<RouteIntelligence.SpeedSection> s,boolean metric){speeds=s==null?Collections.emptyList():s;this.metric=metric;invalidate();}
    void setIncidents(List<LiveTrafficIncidents.Event> data,long at){incidents=data==null?Collections.emptyList():data;incidentAt=at;invalidate();}
    void setBuildings(boolean value){extrude=value;invalidate();}
    void setRoadside(List<int[]> points){roadside=points;invalidate();}
    void setCameraChanged(Runnable r){cameraChanged=r;}
    void setGesture(Runnable callback){gesture=callback;}
    void setPaintListener(Runnable callback){paintListener=callback;}
    GeoMath.Point center(){return center;}
    void fit(List<GeoMath.Point> p,int top,int bottom,int side){
        if(p==null||p.size()<2)return;
        if(getWidth()<100||getHeight()<100){postDelayed(()->{if(getWidth()>=100&&getHeight()>=100)fit(p,top,bottom,side);},250);return;}
        double w=1,e=0,n=1,s=0;
        for(GeoMath.Point q:p){double x=NativeMapProjection.x(q.lon),y=NativeMapProjection.y(q.lat);w=Math.min(w,x);e=Math.max(e,x);n=Math.min(n,y);s=Math.max(s,y);}
        double scale=Math.min(Math.max(80,getWidth()-2*side)/Math.max(1e-9,e-w),Math.max(80,getHeight()-top-bottom)/Math.max(1e-9,s-n));
        double z=Math.max(2,Math.min(21,Math.log(scale/256)/Math.log(2)));
        double actual=256*Math.pow(2,z),cy=(n+s)/2-(top-bottom)/(2.0*actual);
        setCamera(new GeoMath.Point(NativeMapProjection.lat(cy),NativeMapProjection.lon((w+e)/2)),z,0,0);
    }
    void setZoom(double value){zoom=Math.max(2,Math.min(21,value));invalidate();if(cameraChanged!=null)cameraChanged.run();}
    double zoom(){return zoom;}
    void setNight(boolean value){night=value;invalidate();}
    void setCamera(GeoMath.Point p,double z,double bearing,double tilt){if(p==null)return;center=p;zoom=Math.max(2,Math.min(21,z));this.bearing=bearing;this.tilt=tilt;invalidate();}
    void setProgress(double value,GeoMath.Point vehicle,double turnOffset){progress=Math.max(0,value);this.vehicle=vehicle;turn=turnOffset;invalidate();}
    double bearing(){return bearing;}
    int roadsDrawn(){return roadsDrawn;}int routeDrawn(){return routeDrawn;}int buildingsDrawn(){return buildingsDrawn;}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private int color(int day,int dark){return night?dark:day;}
    private NativeMapProjection.Camera camera(){return new NativeMapProjection.Camera(center.lat,center.lon,zoom,bearing,tilt,getWidth(),getHeight());}
    private void point(NativeMapProjection.Camera c,GeoMath.Point p){c.screen(NativeMapProjection.x(p.lon),NativeMapProjection.y(p.lat),screen);}
    private boolean visible(NativeMapScene.Shape f,NativeMapProjection.Camera c){
        float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
        for(int i=0;i<4;i++){c.screen((i&1)==0?f.w:f.e,(i&2)==0?f.s:f.n,screen);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1]))return false;minX=Math.min(minX,screen[0]);maxX=Math.max(maxX,screen[0]);minY=Math.min(minY,screen[1]);maxY=Math.max(maxY,screen[1]);}
        return maxX>=-30&&minX<=getWidth()+30&&maxY>=-30&&minY<=getHeight()+30;
    }
    private void makePath(NativeMapScene.Shape f,NativeMapProjection.Camera c){
        path.reset();path.setFillType(Path.FillType.EVEN_ODD);for(int j=0;j<f.parts.length;j++){int start=f.parts[j],end=j+1<f.parts.length?f.parts[j+1]:f.xy.length;for(int i=start;i<end;i+=2){c.screen(f.xy[i],f.xy[i+1],screen);if(i==start)path.moveTo(screen[0],screen[1]);else path.lineTo(screen[0],screen[1]);}if(f.area)path.close();}
    }
    private void stroke(Canvas c,int color,float width){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(width));c.drawPath(path,paint);}
    private void fill(Canvas c,int color){paint.setColor(color);paint.setStyle(Paint.Style.FILL);c.drawPath(path,paint);}
    private void routePath(Canvas canvas,NativeMapProjection.Camera c,List<GeoMath.Point> points,int color,float width){
        if(points.size()<2)return;path.reset();boolean first=true;for(GeoMath.Point p:points){point(c,p);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1])){first=true;continue;}if(first){path.moveTo(screen[0],screen[1]);first=false;}else path.lineTo(screen[0],screen[1]);}
        stroke(canvas,color,width);
    }
    @Override protected void onDraw(Canvas canvas){
        long frameStart=System.nanoTime();
        super.onDraw(canvas);canvas.drawColor(color(0xffeef1e9,0xff132634));roadsDrawn=routeDrawn=buildingsDrawn=0;
        if(center==null||getWidth()==0)return;
        NativeMapProjection.Camera c=camera();paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);occupied.clear();
        ArrayList<NativeMapScene.Shape> visible=visibleShapes;visible.clear();
        if(scene!=null)for(NativeMapScene.Shape f:scene.shapes){if(f.kind==NativeMapScene.BUILDING&&(!showBuildings||zoom<15)||!visible(f,c))continue;visible.add(f);}
        for(NativeMapScene.Shape f:visible){
            if(!f.area)continue;makePath(f,c);
            if(f.kind==NativeMapScene.BUILDING){fill(canvas,color(0xffd7cbb9,0xff334654));stroke(canvas,color(0xffc2b6a3,0xff455867),.7f);buildingsDrawn++;
                // Oblique height-based extrusion, bounded for readability. Not a perspective GPU mesh.
                if(extrude&&tilt>10&&zoom>=16&&f.height>0&&f.parts.length==1){
                    float h=(float)Math.min(dp(85),f.height*c.scale/(40075016.7*Math.max(.15,Math.cos(Math.toRadians(center.lat))))*Math.sin(Math.toRadians(tilt)));
                    if(h>1){for(int i=0;i<f.xy.length-2;i+=2){c.screen(f.xy[i],f.xy[i+1],screen);c.screen(f.xy[i+2],f.xy[i+3],corner);path.reset();path.moveTo(screen[0],screen[1]);path.lineTo(corner[0],corner[1]);path.lineTo(corner[0],corner[1]-h);path.lineTo(screen[0],screen[1]-h);path.close();fill(canvas,color(0xffb7aa97,0xff283b4a));}makePath(f,c);path.offset(0,-h);fill(canvas,color(0xffe0d4c3,0xff425869));stroke(canvas,color(0xffc2b6a3,0xff526575),.7f);}
                }
            }
            else if(f.kind==NativeMapScene.WATER)fill(canvas,color(0xffa6d5e9,0xff254d6b));
            else fill(canvas,color(0xffdce9ce,0xff243c37));
        }
        for(int pass=0;pass<2;pass++)for(NativeMapScene.Shape f:visible){
            if(f.area||f.bridge!=(pass==1))continue;makePath(f,c);
            if(f.kind==NativeMapScene.WATER){stroke(canvas,color(0xff8bc6e2,0xff376989),2);continue;}
            if(f.kind==NativeMapScene.RAIL){stroke(canvas,color(0xff969c9e,0xff687782),1);continue;}
            if(f.kind<NativeMapScene.PATH||f.kind==NativeMapScene.LABEL)continue;
            float width=f.kind==NativeMapScene.MOTORWAY?7:f.kind==NativeMapScene.MAIN?5:f.kind==NativeMapScene.LOCAL?3.4f:1.2f;
            width*=Math.max(.7f,Math.min(1.6f,(float)(1+(zoom-16)*.12)));
            stroke(canvas,color(0xffb1b8b9,0xff10202d),width+1.7f);
            stroke(canvas,f.tunnel?color(0xffd6d8d6,0xff465461):f.kind==NativeMapScene.MOTORWAY?color(0xfff1cd9d,0xffa18b68):f.kind==NativeMapScene.MAIN?color(0xffffe7a7,0xff667178):color(0xffffffff,0xff50616d),width);
            roadsDrawn++;
        }
        if(zoom>=2){paint.setTextSize(dp(11));paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setStyle(Paint.Style.FILL);
            for(NativeMapScene.Shape f:visible){
                if(f.name.isEmpty()||(f.kind!=NativeMapScene.LABEL&&(zoom<15||f.kind<NativeMapScene.LOCAL||f.kind>NativeMapScene.MOTORWAY))||occupied.size()>=160)continue;
                int i=(f.xy.length/4)*2;c.screen(f.xy[i],f.xy[i+1],screen);float x=screen[0],y=screen[1],w=paint.measureText(f.name);
                RectF box=new RectF(x-w/2-dp(3),y-dp(12),x+w/2+dp(3),y+dp(3));if(box.left<0||box.right>getWidth()||box.top<0||box.bottom>getHeight())continue;
                boolean collision=false;for(RectF other:occupied)if(RectF.intersects(other,box)){collision=true;break;}if(collision)continue;
                occupied.add(box);paint.setColor(color(0xfff8faf7,0xff142737));canvas.drawRoundRect(box,dp(3),dp(3),paint);
                paint.setColor(color(0xff344451,0xffe4edf3));canvas.drawText(f.name,x-w/2,y,paint);
            }
        }
        if(route!=null){routePath(canvas,c,route.full(),color(0xffaeb9bb,0xff516270),6);routePath(canvas,c,route.remaining(progress),0xff143d64,8);routePath(canvas,c,route.remaining(progress),0xff16a6f4,5);
            if(route.full().size()>1)routeDrawn=1;
            arrow(canvas,c,turn);arrow(canvas,c,secondTurn);
            for(RouteIntelligence.SpeedSection section:speeds){
                if(section.start<=progress||section.start>progress+700||section.start>=route.length)continue;
                point(c,route.at(section.start));if(screen[0]<0||screen[0]>getWidth()||screen[1]<0||screen[1]>getHeight())continue;
                paint.setStyle(Paint.Style.FILL);paint.setColor(0xffb7232c);canvas.drawCircle(screen[0],screen[1],dp(11),paint);paint.setColor(Color.WHITE);canvas.drawCircle(screen[0],screen[1],dp(8),paint);
                paint.setColor(0xff192635);paint.setTextSize(dp(9));String t=String.valueOf(metric?section.kmh:(int)Math.round(section.kmh/1.609344));canvas.drawText(t,screen[0]-paint.measureText(t)/2,screen[1]+dp(3),paint);
            }
        }
        if(zoom>=14)for(int[] row:roadside){
            point(c,new GeoMath.Point(row[0]/1e7,row[1]/1e7));float x=screen[0],y=screen[1];if(x<0||x>getWidth()||y<0||y>getHeight()||(zoom<15&&row[2]!=4&&row[2]!=1))continue;
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xff263d50);canvas.drawRoundRect(x-dp(7),y-dp(9),x+dp(7),y+dp(9),dp(3),dp(3),paint);
            if(row[2]==4){paint.setColor(0xffe84545);canvas.drawCircle(x,y-dp(4),dp(1.7f),paint);paint.setColor(0xffefb33f);canvas.drawCircle(x,y,dp(1.7f),paint);paint.setColor(0xff4eb477);canvas.drawCircle(x,y+dp(4),dp(1.7f),paint);}
            else{String t=row[2]==1?"C":row[2]==3?"~":row[2]==5?"Z":row[2]==6?"Pe":row[2]==7?"Pu":row[2]==8?"T":row[2]==9?"Pa":row[2]==10?"H":"P";paint.setColor(Color.WHITE);paint.setTextSize(dp(8));canvas.drawText(t,x-paint.measureText(t)/2,y+dp(3),paint);}
        }
        if(System.currentTimeMillis()-incidentAt>=0&&System.currentTimeMillis()-incidentAt<=LiveTrafficIncidents.MAX_AGE_MS)
            for(LiveTrafficIncidents.Event event:incidents)if(event.present(System.currentTimeMillis())&&!event.points.isEmpty())marker(canvas,c,event.points.get(0),0xffbd5426,"T!");
        marker(canvas,c,destination,0xffbf3038,"D");marker(canvas,c,entrance,0xff267a58,"E");marker(canvas,c,parked,0xff426987,"P");
        if(vehicle!=null){point(c,vehicle);float x=screen[0],y=screen[1];canvas.save();canvas.translate(x,y);canvas.rotate(Double.isFinite(vehicleBearing)?(float)(vehicleBearing-bearing):0);
            path.reset();path.moveTo(0,-dp(13));path.lineTo(dp(9),dp(10));path.lineTo(0,dp(5));path.lineTo(-dp(9),dp(10));path.close();fill(canvas,0xff007ac9);stroke(canvas,Color.WHITE,2);canvas.restore();}
        if(scene==null){paint.setColor(color(0xff344451,0xfff1f4f5));paint.setTextSize(dp(12));paint.setStyle(Paint.Style.FILL);canvas.drawText("Map coverage unavailable",dp(10),getHeight()*.45f,paint);}
        long elapsed=System.nanoTime()-frameStart;frameCount++;drawTotalNanos+=elapsed;drawMaxNanos=Math.max(drawMaxNanos,elapsed);
        if(paintListener!=null)paintListener.run();
        paint.setStyle(Paint.Style.FILL);paint.setTextSize(dp(10));paint.setColor(color(0xff344451,0xfff1f4f5));canvas.drawText("© OpenStreetMap contributors · DriveMate native",dp(8),getHeight()-dp(8),paint);
    }
    private void marker(Canvas canvas,NativeMapProjection.Camera c,GeoMath.Point p,int color,String text){
        if(p==null)return;point(c,p);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1]))return;paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawCircle(screen[0],screen[1],dp(9),paint);paint.setColor(Color.WHITE);paint.setTextSize(dp(10));canvas.drawText(text,screen[0]-paint.measureText(text)/2,screen[1]+dp(3),paint);
    }
    private void arrow(Canvas canvas,NativeMapProjection.Camera c,double offset){
        if(route==null||!Double.isFinite(offset)||offset<=progress||offset>=route.length)return;
        GeoMath.Point p=route.at(offset),q=route.at(Math.min(route.length,offset+18));point(c,p);float ax=screen[0],ay=screen[1];point(c,q);
        float dx=screen[0]-ax,dy=screen[1]-ay,len=(float)Math.hypot(dx,dy);if(!Float.isFinite(len)||len<=1)return;
        dx/=len;dy/=len;float size=dp(11);path.reset();path.moveTo(ax+dx*size,ay+dy*size);path.lineTo(ax-dx*size*.6f-dy*size*.65f,ay-dy*size*.6f+dx*size*.65f);path.lineTo(ax-dx*size*.6f+dy*size*.65f,ay-dy*size*.6f-dx*size*.65f);path.close();fill(canvas,Color.WHITE);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        scale.onTouchEvent(e);if(center==null)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){if(cameraChanged!=null)cameraChanged.run();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(gesture!=null)gesture.run();float x=e.getX(),y=e.getY();if(!scale.isInProgress()&&e.getPointerCount()==1){NativeMapProjection.Camera c=camera();center=c.unproject(c.anchorX+lastX-x,c.anchorY+lastY-y);invalidate();}lastX=x;lastY=y;return true;}
        return true;
    }
}
