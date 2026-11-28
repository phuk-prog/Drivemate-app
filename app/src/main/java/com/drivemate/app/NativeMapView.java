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
    private final HashMap<String,List<float[]>> labelNames=new HashMap<>();
    private boolean loading;
    private NativeMapScene scene,background;
    private GeoMath.Point center,vehicle;
    private RouteRenderGeometry route;
    private double zoom=16,bearing=0,tilt=0,progress=0,turn=Double.NaN,secondTurn=Double.NaN,vehicleBearing=Double.NaN;
    private GeoMath.Point destination,entrance,parked;
    private String destinationLabel="";
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
        super(context);setContentDescription("DriveMate map");
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){setZoom(zoom+Math.log(d.getScaleFactor())/Math.log(2));return true;}
        });
    }
    void setScene(NativeMapScene value){scene=value;route=new RouteRenderGeometry(value==null?Collections.emptyList():value.route);if(value!=null&&!value.route.isEmpty()){center=route.at(route.length*.4);vehicle=center;}invalidate();}
    void setSceneOnly(NativeMapScene value){scene=value;invalidate();}
    /** Base tiles drawn under the scene: areas everywhere, roads only where the scene has none. */
    void setBackgroundScene(NativeMapScene value){background=value;invalidate();}
    void setLoading(boolean value){if(loading!=value){loading=value;invalidate();}}
    private java.util.function.Supplier<String> debug;
    void setDebug(java.util.function.Supplier<String> value){debug=value;invalidate();}
    void setRoute(List<GeoMath.Point> value){route=new RouteRenderGeometry(value);progress=0;turn=secondTurn=Double.NaN;invalidate();}
    void setVehicle(GeoMath.Point p,double direction){vehicle=p;vehicleBearing=direction;invalidate();}
    void setMarkers(GeoMath.Point d,GeoMath.Point e,GeoMath.Point p){destination=d;entrance=e;parked=p;invalidate();}
    void setDestinationLabel(String label){destinationLabel=label==null?"":label.trim();invalidate();}
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
        for(int i=0;i<4;i++){c.screen((i&1)==0?f.w:f.e,(i&2)==0?f.s:f.n,screen);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1]))return !f.area;minX=Math.min(minX,screen[0]);maxX=Math.max(maxX,screen[0]);minY=Math.min(minY,screen[1]);maxY=Math.max(maxY,screen[1]);}
        return maxX>=-30&&minX<=getWidth()+30&&maxY>=-30&&minY<=getHeight()+30;
    }
    private boolean segmentVisible(float[] a,float[] b){
        // Conservative bounding rectangle; Canvas clips remaining geometry.
        return Math.max(a[0],b[0])>=-24&&Math.min(a[0],b[0])<=getWidth()+24&&Math.max(a[1],b[1])>=-24&&Math.min(a[1],b[1])<=getHeight()+24;
    }
    private boolean makePath(NativeMapScene.Shape f,NativeMapProjection.Camera c){
        path.reset();path.setFillType(Path.FillType.EVEN_ODD);boolean drawn=false;
        for(int j=0;j<f.parts.length;j++){
            int start=f.parts[j],end=j+1<f.parts.length?f.parts[j+1]:f.xy.length;
            if(f.area){for(int i=start;i<end;i+=2){c.screen(f.xy[i],f.xy[i+1],screen);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1])){path.reset();return false;}if(i==start)path.moveTo(screen[0],screen[1]);else path.lineTo(screen[0],screen[1]);}path.close();drawn=true;}
            else for(int i=start+2;i<end;i+=2)if(c.segment(f.xy[i-2],f.xy[i-1],f.xy[i],f.xy[i+1],screen,corner)&&segmentVisible(screen,corner)){path.moveTo(screen[0],screen[1]);path.lineTo(corner[0],corner[1]);drawn=true;}
        }
        return drawn;
    }
    private void stroke(Canvas c,int color,float width){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(width));c.drawPath(path,paint);}
    private void fill(Canvas c,int color){paint.setColor(color);paint.setStyle(Paint.Style.FILL);c.drawPath(path,paint);}
    private boolean routePath(Canvas canvas,NativeMapProjection.Camera c,List<GeoMath.Point> points,int color,float width){
        if(points.size()<2)return false;path.reset();boolean drawn=false;
        for(int i=1;i<points.size();i++){GeoMath.Point a=points.get(i-1),b=points.get(i);if(c.segment(NativeMapProjection.x(a.lon),NativeMapProjection.y(a.lat),NativeMapProjection.x(b.lon),NativeMapProjection.y(b.lat),screen,corner)&&segmentVisible(screen,corner)){path.moveTo(screen[0],screen[1]);path.lineTo(corner[0],corner[1]);drawn=true;}}
        if(drawn)stroke(canvas,color,width);return drawn;
    }
    @Override protected void onDraw(Canvas canvas){
        long frameStart=System.nanoTime();
        super.onDraw(canvas);canvas.drawColor(color(0xffeef1e9,0xff132634));roadsDrawn=routeDrawn=buildingsDrawn=0;
        if(center==null||getWidth()==0)return;
        NativeMapProjection.Camera c=camera();paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);occupied.clear();
        ArrayList<NativeMapScene.Shape> visible=visibleShapes;visible.clear();
        if(background!=null&&background!=scene){
            double[] b=scene==null?null:scene.bounds;
            double bw=b==null?0:NativeMapProjection.x(b[0]),be=b==null?0:NativeMapProjection.x(b[2]),bn=b==null?0:NativeMapProjection.y(b[3]),bs=b==null?0:NativeMapProjection.y(b[1]);
            for(NativeMapScene.Shape f:background.shapes){
                if(f.kind==NativeMapScene.BUILDING&&(!showBuildings||zoom<15)||!visible(f,c))continue;
                // Never double-draw a road inside the verified corridor: its own geometry is exact.
                if((!f.area||f.kind==NativeMapScene.BUILDING)&&b!=null){double mx=(f.w+f.e)/2,my=(f.s+f.n)/2;if(mx>=bw&&mx<=be&&my>=Math.min(bn,bs)&&my<=Math.max(bn,bs))continue;}
                visible.add(f);
            }
        }
        if(scene!=null)for(NativeMapScene.Shape f:scene.shapes){if(f.kind==NativeMapScene.BUILDING&&(!showBuildings||zoom<15)||!visible(f,c))continue;visible.add(f);}
        for(NativeMapScene.Shape f:visible){
            if(!f.area||!makePath(f,c))continue;
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
            if(f.area||f.bridge!=(pass==1)||!makePath(f,c))continue;
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
            // Places first, then motorways, main roads and streets; a name is never repeated close by.
            labelNames.clear();
            for(int pass=0;pass<4;pass++)for(NativeMapScene.Shape f:visible){
                int want=pass==0?NativeMapScene.LABEL:pass==1?NativeMapScene.MOTORWAY:pass==2?NativeMapScene.MAIN:NativeMapScene.LOCAL;
                if(f.kind!=want||f.name.isEmpty()||!MapLayers.labelVisible(f.kind,zoom)||occupied.size()>=160)continue;
                int i=(f.xy.length/4)*2;c.screen(f.xy[i],f.xy[i+1],screen);float x=screen[0],y=screen[1],w=paint.measureText(f.name);if(!Float.isFinite(x)||!Float.isFinite(y))continue;
                RectF box=new RectF(x-w/2-dp(3),y-dp(12),x+w/2+dp(3),y+dp(3));if(box.left<0||box.right>getWidth()||box.top<0||box.bottom>getHeight())continue;
                boolean collision=false;for(RectF other:occupied)if(RectF.intersects(other,box)){collision=true;break;}if(collision)continue;
                List<float[]> same=labelNames.get(f.name);if(MapLayers.repeatsNearby(x,y,same))continue;
                if(same==null){same=new ArrayList<>();labelNames.put(f.name,same);}same.add(new float[]{x,y});
                occupied.add(box);paint.setColor(color(0xfff8faf7,0xff142737));canvas.drawRoundRect(box,dp(3),dp(3),paint);
                paint.setColor(color(0xff344451,0xffe4edf3));canvas.drawText(f.name,x-w/2,y,paint);
            }
        }
        if(route!=null){boolean full=routePath(canvas,c,route.full(),color(0xffaeb9bb,0xff516270),6);List<GeoMath.Point> remaining=route.remaining(progress);routePath(canvas,c,remaining,0xff143d64,8);boolean ahead=routePath(canvas,c,remaining,0xff16a6f4,5);
            if(full||ahead)routeDrawn=1;
            // One bold manoeuvre arrow drawn on the road at the junction; a second turn close
            // behind it (a roundabout exit, a quick left-right) is folded into the same arrow.
            boolean merged=Double.isFinite(secondTurn)&&Double.isFinite(turn)&&secondTurn>turn&&secondTurn-turn<=TURN_ARROW_MERGE_METRES;
            turnArrow(canvas,c,turn,merged?secondTurn-turn+TURN_ARROW_AFTER_METRES:TURN_ARROW_AFTER_METRES);
            if(!merged)arrow(canvas,c,secondTurn);
            int previousKmh=-1;double lastShown=Double.NaN;
            for(RouteIntelligence.SpeedSection section:speeds){
                int prev=previousKmh;previousKmh=section.kmh;
                if(section.start>progress+700||section.start>=route.length)continue;
                // No sign at the car (the speed cluster shows it), for a repeat, or stacked on another.
                if(!MapLayers.showLimitMarker(section.kmh,prev,section.start,lastShown,progress))continue;
                lastShown=section.start;
                point(c,route.at(section.start));if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1])||screen[0]<0||screen[0]>getWidth()||screen[1]<0||screen[1]>getHeight())continue;
                paint.setStyle(Paint.Style.FILL);paint.setColor(0xffb7232c);canvas.drawCircle(screen[0],screen[1],dp(11),paint);paint.setColor(Color.WHITE);canvas.drawCircle(screen[0],screen[1],dp(8),paint);
                paint.setColor(0xff192635);paint.setTextSize(dp(9));String t=String.valueOf(metric?section.kmh:(int)Math.round(section.kmh/1.609344));canvas.drawText(t,screen[0]-paint.measureText(t)/2,screen[1]+dp(3),paint);
            }
        }
        List<GeoMath.Point> hazardTrack=hazardTrack();
        if(zoom>=14)for(int[] row:roadside){
            GeoMath.Point rp=new GeoMath.Point(row[0]/1e7,row[1]/1e7);
            // With a route: only hazards on the road ahead, never side streets.
            if(hazardTrack!=null&&!nearTrack(rp,hazardTrack))continue;
            point(c,rp);float x=screen[0],y=screen[1];if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>getWidth()||y<0||y>getHeight()||(zoom<15&&row[2]!=4&&row[2]!=1))continue;
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xff263d50);canvas.drawRoundRect(x-dp(7),y-dp(9),x+dp(7),y+dp(9),dp(3),dp(3),paint);
            if(row[2]==4){paint.setColor(0xffe84545);canvas.drawCircle(x,y-dp(4),dp(1.7f),paint);paint.setColor(0xffefb33f);canvas.drawCircle(x,y,dp(1.7f),paint);paint.setColor(0xff4eb477);canvas.drawCircle(x,y+dp(4),dp(1.7f),paint);}
            else{String t=row[2]==1?"C":row[2]==3?"~":row[2]==5?"Z":row[2]==6?"Pe":row[2]==7?"Pu":row[2]==8?"T":row[2]==9?"Pa":row[2]==10?"H":"P";paint.setColor(Color.WHITE);paint.setTextSize(dp(8));canvas.drawText(t,x-paint.measureText(t)/2,y+dp(3),paint);}
        }
        if(System.currentTimeMillis()-incidentAt>=0&&System.currentTimeMillis()-incidentAt<=LiveTrafficIncidents.MAX_AGE_MS)
            for(LiveTrafficIncidents.Event event:incidents)if(event.present(System.currentTimeMillis())&&!event.points.isEmpty())marker(canvas,c,event.points.get(0),0xffbd5426,"T!");
        destinationPin(canvas,c);
        // An entrance on top of the pin is drawn beside it, linked by a short line.
        if(entrance!=null&&destination!=null&&GeoMath.distance(entrance,destination)<MapLayers.MARKER_OVERLAP_METRES){
            point(c,destination);float dx=screen[0],dy=screen[1];
            if(Float.isFinite(dx)&&Float.isFinite(dy)){float ex=dx+dp(26),ey=dy+dp(4);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(0xff267a58);canvas.drawLine(dx,dy,ex,ey,paint);
                paint.setStyle(Paint.Style.FILL);canvas.drawCircle(ex,ey,dp(9),paint);paint.setColor(Color.WHITE);paint.setTextSize(dp(10));canvas.drawText("E",ex-paint.measureText("E")/2,ey+dp(3.5f),paint);}
        }else marker(canvas,c,entrance,0xff267a58,"E");marker(canvas,c,parked,0xff426987,"P");
        if(vehicle!=null){point(c,vehicle);float x=screen[0],y=screen[1];if(Float.isFinite(x)&&Float.isFinite(y)){canvas.save();canvas.translate(x,y);canvas.rotate(Double.isFinite(vehicleBearing)?(float)(vehicleBearing-bearing):0);
            path.reset();path.moveTo(0,-dp(13));path.lineTo(dp(9),dp(10));path.lineTo(0,dp(5));path.lineTo(-dp(9),dp(10));path.close();fill(canvas,0xff007ac9);stroke(canvas,Color.WHITE,2);canvas.restore();}}
        if(scene==null&&background==null||loading){
            // A small status chip, never a bare error line across the map.
            String t=loading?"Loading map…":"Map not available here yet";
            paint.setTextSize(dp(13));paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setStyle(Paint.Style.FILL);float w=paint.measureText(t);
            RectF chip=new RectF(getWidth()/2f-w/2-dp(12),getHeight()*.5f-dp(16),getWidth()/2f+w/2+dp(12),getHeight()*.5f+dp(14));
            paint.setColor(color(0xee0f2236,0xee0f2236));canvas.drawRoundRect(chip,dp(15),dp(15),paint);
            paint.setColor(Color.WHITE);canvas.drawText(t,chip.left+dp(12),chip.bottom-dp(10),paint);paint.setTypeface(Typeface.DEFAULT);
        }
        if(debug!=null){
            String t=debug.get()+String.format(java.util.Locale.UK," · draw %.1f ms",(System.nanoTime()-frameStart)/1e6);
            paint.setTextSize(dp(10));paint.setTypeface(Typeface.MONOSPACE);paint.setStyle(Paint.Style.FILL);float w=paint.measureText(t);
            paint.setColor(0xcc000000);float dy=getHeight()*.42f;canvas.drawRect(0,dy-dp(13),Math.min(getWidth(),w+dp(12)),dy+dp(4),paint);
            paint.setColor(Color.WHITE);canvas.drawText(t,dp(6),dy,paint);paint.setTypeface(Typeface.DEFAULT);
        }
        long elapsed=System.nanoTime()-frameStart;frameCount++;drawTotalNanos+=elapsed;drawMaxNanos=Math.max(drawMaxNanos,elapsed);
        if(paintListener!=null)paintListener.run();
        paint.setStyle(Paint.Style.FILL);paint.setTextSize(dp(10));paint.setColor(color(0xff344451,0xfff1f4f5));canvas.drawText("© OpenStreetMap contributors · OpenFreeMap · OpenMapTiles",dp(8),getHeight()-dp(8),paint);
    }
    private void marker(Canvas canvas,NativeMapProjection.Camera c,GeoMath.Point p,int color,String text){
        if(p==null)return;point(c,p);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1]))return;paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawCircle(screen[0],screen[1],dp(9),paint);paint.setColor(Color.WHITE);paint.setTextSize(dp(10));canvas.drawText(text,screen[0]-paint.measureText(text)/2,screen[1]+dp(3),paint);
    }
    static final double TURN_ARROW_BEFORE_METRES=22,TURN_ARROW_AFTER_METRES=28,TURN_ARROW_MERGE_METRES=110;
    static final double HAZARD_AHEAD_METRES=300,HAZARD_NEAR_METRES=18;
    /** Sample of the route ahead used to keep only on-route hazards, or null when there is no route. */
    private List<GeoMath.Point> hazardTrack(){
        if(route==null||route.length<=0)return null;
        ArrayList<GeoMath.Point> out=new ArrayList<>();
        double end=Math.min(route.length,progress+HAZARD_AHEAD_METRES);
        trackS=90;trackN=-90;trackW=180;trackE=-180;
        for(double d=Math.max(0,progress-10);d<=end;d+=8){GeoMath.Point q=route.at(d);out.add(q);
            trackS=Math.min(trackS,q.lat);trackN=Math.max(trackN,q.lat);trackW=Math.min(trackW,q.lon);trackE=Math.max(trackE,q.lon);}
        double m=HAZARD_NEAR_METRES/111000.0,mx=m/Math.max(.2,Math.cos(Math.toRadians((trackS+trackN)/2)));
        trackS-=m;trackN+=m;trackW-=mx;trackE+=mx;
        return out;
    }
    private double trackS,trackN,trackW,trackE;
    private boolean nearTrack(GeoMath.Point p,List<GeoMath.Point> track){
        // Cheap box test first: the roadside list covers a whole area.
        if(p.lat<trackS||p.lat>trackN||p.lon<trackW||p.lon>trackE)return false;
        for(GeoMath.Point q:track)if(GeoMath.distance(p,q)<=HAZARD_NEAR_METRES)return true;
        return false;
    }
    /** Waze-style manoeuvre arrow: a white band along the route through the junction, ending in a head. */
    private void turnArrow(Canvas canvas,NativeMapProjection.Camera c,double offset,double after){
        if(route==null||!Double.isFinite(offset)||offset<=progress||offset>=route.length)return;
        double start=Math.max(progress,offset-TURN_ARROW_BEFORE_METRES),end=Math.min(route.length,offset+after);
        if(end-start<10)return;
        path.reset();int n=0;float lx=0,ly=0,px=0,py=0;
        for(double d=start;d<=end+.01;d+=Math.max(2,(end-start)/40)){
            point(c,route.at(Math.min(d,end)));if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1]))return;
            if(n==0)path.moveTo(screen[0],screen[1]);else path.lineTo(screen[0],screen[1]);
            px=lx;py=ly;lx=screen[0];ly=screen[1];n++;
        }
        if(n<3)return;
        float dx=lx-px,dy=ly-py,len=(float)Math.hypot(dx,dy);if(!Float.isFinite(len)||len<.5f)return;dx/=len;dy/=len;
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        stroke(canvas,0xff10202d,9.5f);stroke(canvas,Color.WHITE,6.5f);
        float size=dp(13);path.reset();
        path.moveTo(lx+dx*size,ly+dy*size);
        path.lineTo(lx-dy*size*.85f,ly+dx*size*.85f);path.lineTo(lx+dy*size*.85f,ly-dx*size*.85f);path.close();
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.6f));paint.setColor(0xff10202d);canvas.drawPath(path,paint);
        fill(canvas,Color.WHITE);
    }
    /** Destination: a drop pin with a flag and, when known, the place name. */
    private void destinationPin(Canvas canvas,NativeMapProjection.Camera c){
        if(destination==null)return;point(c,destination);float x=screen[0],y=screen[1];if(!Float.isFinite(x)||!Float.isFinite(y))return;
        float r=dp(13),cy=y-dp(22);
        path.reset();path.moveTo(x,y);path.lineTo(x-r*.7f,cy+r*.55f);path.lineTo(x+r*.7f,cy+r*.55f);path.close();fill(canvas,0xffd7262e);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xffd7262e);canvas.drawCircle(x,cy,r,paint);
        paint.setColor(Color.WHITE);canvas.drawCircle(x,cy,r*.62f,paint);
        paint.setColor(0xffd7262e);paint.setTextSize(dp(12));paint.setTypeface(Typeface.DEFAULT_BOLD);String flag="⚑";canvas.drawText(flag,x-paint.measureText(flag)/2,cy+dp(4.5f),paint);
        if(!destinationLabel.isEmpty()){
            String t=destinationLabel.length()>28?destinationLabel.substring(0,27)+"…":destinationLabel;
            paint.setTextSize(dp(12));float w=paint.measureText(t);RectF box=new RectF(x-w/2-dp(6),cy-r-dp(24),x+w/2+dp(6),cy-r-dp(4));
            paint.setColor(color(0xffffffff,0xff0f2236));canvas.drawRoundRect(box,dp(6),dp(6),paint);
            paint.setColor(color(0xff10202d,0xffffffff));canvas.drawText(t,x-w/2,box.bottom-dp(5.5f),paint);
        }
        paint.setTypeface(Typeface.DEFAULT);
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
