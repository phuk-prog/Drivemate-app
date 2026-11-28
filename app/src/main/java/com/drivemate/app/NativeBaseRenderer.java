package com.drivemate.app;
import android.graphics.*;
import java.util.*;

/** Draws the base map (areas, roads, names) for one camera. One instance per thread: it owns its paths and paint.
 * Shapes of the same style are batched into one Path, so every road class is one draw call, casings
 * are all drawn before fills (no dark rings where roads meet or tiles join), and panning stays cheap. */
final class NativeBaseRenderer {
    static final class Frame {
        NativeMapProjection.Camera camera;NativeMapScene scene,background,previous;
        double zoom,tilt;int width,height;float density;boolean night,showBuildings,extrude;
        int labelTop,labelBottom;
        /** GPU engine draws areas and roads: clear to transparent and draw names only. */
        boolean labelsOnly;
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),halo=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    // [bridge pass][kind PATH..MOTORWAY][tunnel]
    private final Path[][][] roads=new Path[2][4][2];
    private final Path water=new Path(),green=new Path(),buildings=new Path(),waterLines=new Path(),rail=new Path();
    private final float[] screen=new float[2],corner=new float[2];
    private final ArrayList<NativeMapScene.Shape> visible=new ArrayList<>();
    private final ArrayList<RectF> occupied=new ArrayList<>();
    private final HashMap<String,List<float[]>> labelNames=new HashMap<>();
    private Frame f;private int roadsDrawn,buildingsDrawn;
    /** Named shapes per scene, worked out once per scene rather than scanning every shape each frame. */
    private final NativeMapScene[] namedFor=new NativeMapScene[2];
    @SuppressWarnings("unchecked") private final ArrayList<NativeMapScene.Shape>[] namedCache=new ArrayList[]{new ArrayList<>(),new ArrayList<>()};
    private List<NativeMapScene.Shape> named(NativeMapScene sc,int slot){
        if(namedFor[slot]!=sc){
            ArrayList<NativeMapScene.Shape> out=namedCache[slot];out.clear();
            for(NativeMapScene.Shape s:sc.shapes)if(!s.name.isEmpty()&&(s.kind==NativeMapScene.LABEL||s.kind>=NativeMapScene.LOCAL&&s.kind<=NativeMapScene.MOTORWAY))out.add(s);
            namedFor[slot]=sc;
        }
        return namedCache[slot];
    }
    NativeBaseRenderer(){
        for(Path[][] a:roads)for(Path[] b:a)for(int i=0;i<b.length;i++)b[i]=new Path();
        water.setFillType(Path.FillType.WINDING);green.setFillType(Path.FillType.WINDING);buildings.setFillType(Path.FillType.WINDING);
        halo.setStyle(Paint.Style.STROKE);halo.setStrokeJoin(Paint.Join.ROUND);
    }
    int roadsDrawn(){return roadsDrawn;}int buildingsDrawn(){return buildingsDrawn;}
    private float dp(float n){return n*f.density;}
    private int color(int day,int dark){return f.night?dark:day;}

    void render(Canvas canvas,Frame frame){
        f=frame;roadsDrawn=buildingsDrawn=0;
        if(f.labelsOnly){
            NativeMapProjection.Camera c=f.camera;visible.clear();
            if(f.background!=null)for(NativeMapScene.Shape s:named(f.background,0))if(visible(s,c))visible.add(s);
            if(f.scene!=null&&f.scene!=f.background)for(NativeMapScene.Shape s:named(f.scene,1))if(visible(s,c))visible.add(s);
            labels(canvas,c);
            return;
        }
        canvas.drawColor(color(0xffeef1e9,0xff132634));
        NativeMapProjection.Camera c=f.camera;paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        visible.clear();
        if(f.background!=null&&f.background!=f.scene){
            double[] b=f.scene==null?null:f.scene.bounds;
            double bw=b==null?0:NativeMapProjection.x(b[0]),be=b==null?0:NativeMapProjection.x(b[2]),bn=b==null?0:NativeMapProjection.y(b[3]),bs=b==null?0:NativeMapProjection.y(b[1]);
            for(NativeMapScene.Shape s:f.background.shapes){
                if(s.kind==NativeMapScene.BUILDING&&(!f.showBuildings||f.zoom<15)||!visible(s,c))continue;
                if((!s.area||s.kind==NativeMapScene.BUILDING)&&b!=null){double mx=(s.w+s.e)/2,my=(s.s+s.n)/2;if(mx>=bw&&mx<=be&&my>=Math.min(bn,bs)&&my<=Math.max(bn,bs))continue;}
                visible.add(s);
            }
        }
        // Previous base map: only where the new one has no tiles, and without names (they may be out of date).
        if(f.previous!=null&&f.background!=null&&f.previous!=f.background&&f.background.bounds!=null){
            double[] b=f.background.bounds;
            double bw=NativeMapProjection.x(b[0]),be=NativeMapProjection.x(b[2]),y1=NativeMapProjection.y(b[3]),y2=NativeMapProjection.y(b[1]);
            double bn=Math.min(y1,y2),bs=Math.max(y1,y2);
            ArrayList<NativeMapScene.Shape> older=new ArrayList<>();
            for(NativeMapScene.Shape s:f.previous.shapes){
                if(s.kind==NativeMapScene.LABEL||s.kind==NativeMapScene.BUILDING||!visible(s,c))continue;
                double mx=(s.w+s.e)/2,my=(s.s+s.n)/2;if(mx>=bw&&mx<=be&&my>=bn&&my<=bs)continue;
                older.add(s);
            }
            visible.addAll(0,older);
        }
        if(f.scene!=null)for(NativeMapScene.Shape s:f.scene.shapes){if(s.kind==NativeMapScene.BUILDING&&(!f.showBuildings||f.zoom<15)||!visible(s,c))continue;visible.add(s);}
        areas(canvas,c);lines(canvas,c);labels(canvas,c);
    }
    private boolean visible(NativeMapScene.Shape s,NativeMapProjection.Camera c){
        float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
        for(int i=0;i<4;i++){c.screen((i&1)==0?s.w:s.e,(i&2)==0?s.s:s.n,screen);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1]))return !s.area;minX=Math.min(minX,screen[0]);maxX=Math.max(maxX,screen[0]);minY=Math.min(minY,screen[1]);maxY=Math.max(maxY,screen[1]);}
        return maxX>=-30&&minX<=f.width+30&&maxY>=-30&&minY<=f.height+30;
    }
    private boolean segmentVisible(float[] a,float[] b){
        return Math.max(a[0],b[0])>=-24&&Math.min(a[0],b[0])<=f.width+24&&Math.max(a[1],b[1])>=-24&&Math.min(a[1],b[1])<=f.height+24;
    }
    /** Appends a polygon (all rings) to target; false if any vertex is behind the camera. */
    private boolean appendArea(Path target,NativeMapScene.Shape s,NativeMapProjection.Camera c){
        path.rewind();
        for(int j=0;j<s.parts.length;j++){
            int start=s.parts[j],end=j+1<s.parts.length?s.parts[j+1]:s.xy.length;
            for(int i=start;i<end;i+=2){c.screen(s.xy[i],s.xy[i+1],screen);if(!Float.isFinite(screen[0])||!Float.isFinite(screen[1]))return false;if(i==start)path.moveTo(screen[0],screen[1]);else path.lineTo(screen[0],screen[1]);}
            path.close();
        }
        target.addPath(path);return true;
    }
    /** Appends a polyline, joining consecutive visible segments into one continuous run. */
    private boolean appendLine(Path target,NativeMapScene.Shape s,NativeMapProjection.Camera c){
        boolean drawn=false;
        for(int j=0;j<s.parts.length;j++){
            int start=s.parts[j],end=j+1<s.parts.length?s.parts[j+1]:s.xy.length;boolean open=false;float lx=0,ly=0;
            for(int i=start+2;i<end;i+=2){
                if(c.segment(s.xy[i-2],s.xy[i-1],s.xy[i],s.xy[i+1],screen,corner)&&segmentVisible(screen,corner)){
                    if(!open||Math.abs(lx-screen[0])>.5f||Math.abs(ly-screen[1])>.5f)target.moveTo(screen[0],screen[1]);
                    target.lineTo(corner[0],corner[1]);lx=corner[0];ly=corner[1];open=true;drawn=true;
                }else open=false;
            }
        }
        return drawn;
    }
    private void areas(Canvas canvas,NativeMapProjection.Camera c){
        water.rewind();green.rewind();buildings.rewind();
        boolean extrude=f.extrude&&f.tilt>10&&f.zoom>=16;
        for(NativeMapScene.Shape s:visible){
            if(!s.area)continue;
            if(s.kind==NativeMapScene.BUILDING){
                if(extrude&&s.height>0&&s.parts.length==1)continue;
                if(appendArea(buildings,s,c))buildingsDrawn++;
            }else if(s.kind==NativeMapScene.WATER)appendArea(water,s,c);
            else appendArea(green,s,c);
        }
        fill(canvas,green,color(0xffdce9ce,0xff243c37));fill(canvas,water,color(0xffa6d5e9,0xff254d6b));
        fill(canvas,buildings,color(0xffd7cbb9,0xff334654));stroke(canvas,buildings,color(0xffc2b6a3,0xff455867),.7f);
        if(extrude)for(NativeMapScene.Shape s:visible)if(s.area&&s.kind==NativeMapScene.BUILDING&&s.height>0&&s.parts.length==1)extruded(canvas,c,s);
    }
    private void extruded(Canvas canvas,NativeMapProjection.Camera c,NativeMapScene.Shape s){
        float h=(float)Math.min(dp(85),s.height*c.scale/(40075016.7*Math.max(.15,Math.cos(Math.toRadians(NativeMapProjection.lat(c.cy)))))*Math.sin(Math.toRadians(f.tilt)));
        path.rewind();
        if(h<=1){buildings.rewind();if(appendArea(buildings,s,c)){fill(canvas,buildings,color(0xffd7cbb9,0xff334654));buildingsDrawn++;}return;}
        Path side=new Path();
        for(int i=0;i<s.xy.length-2;i+=2){c.screen(s.xy[i],s.xy[i+1],screen);c.screen(s.xy[i+2],s.xy[i+3],corner);if(!Float.isFinite(screen[0])||!Float.isFinite(corner[0]))return;side.moveTo(screen[0],screen[1]);side.lineTo(corner[0],corner[1]);side.lineTo(corner[0],corner[1]-h);side.lineTo(screen[0],screen[1]-h);side.close();}
        fill(canvas,side,color(0xffb7aa97,0xff283b4a));
        buildings.rewind();if(!appendArea(buildings,s,c))return;buildings.offset(0,-h);
        fill(canvas,buildings,color(0xffe0d4c3,0xff425869));stroke(canvas,buildings,color(0xffc2b6a3,0xff526575),.7f);buildingsDrawn++;
    }
    private void lines(Canvas canvas,NativeMapProjection.Camera c){
        waterLines.rewind();rail.rewind();
        for(Path[][] a:roads)for(Path[] b:a)for(Path p:b)p.rewind();
        for(NativeMapScene.Shape s:visible){
            if(s.area||s.kind==NativeMapScene.LABEL)continue;
            if(s.kind==NativeMapScene.WATER){appendLine(waterLines,s,c);continue;}
            if(s.kind==NativeMapScene.RAIL){appendLine(rail,s,c);continue;}
            if(s.kind<NativeMapScene.PATH||s.kind>NativeMapScene.MOTORWAY)continue;
            if(appendLine(roads[s.bridge?1:0][s.kind-NativeMapScene.PATH][s.tunnel?1:0],s,c))roadsDrawn++;
        }
        stroke(canvas,waterLines,color(0xff8bc6e2,0xff376989),2);
        stroke(canvas,rail,color(0xff969c9e,0xff687782),1);
        for(int pass=0;pass<2;pass++){
            // All casings first (widest class last so it frames the others), then fills from minor to motorway.
            for(int k=0;k<4;k++){int kind=NativeMapScene.PATH+k;if(!MapLayers.casing(kind,f.zoom))continue;float w=MapLayers.roadWidth(kind,f.zoom)+1.7f;
                for(int t=0;t<2;t++)stroke(canvas,roads[pass][k][t],color(0xffb1b8b9,0xff10202d),w);}
            for(int k=0;k<4;k++){int kind=NativeMapScene.PATH+k;float w=MapLayers.roadWidth(kind,f.zoom);
                int fillColor=kind==NativeMapScene.MOTORWAY?color(0xfff1cd9d,0xffa18b68):kind==NativeMapScene.MAIN?color(0xffffe7a7,0xff667178):color(0xffffffff,0xff50616d);
                // Without casings, minor roads need a darker fill to show against the land colour.
                if(!MapLayers.casing(kind,f.zoom)&&kind<=NativeMapScene.LOCAL)fillColor=color(0xffc9ced0,0xff50616d);
                stroke(canvas,roads[pass][k][0],fillColor,w);
                stroke(canvas,roads[pass][k][1],color(0xffd6d8d6,0xff465461),w);}
        }
    }
    private void labels(Canvas canvas,NativeMapProjection.Camera c){
        occupied.clear();labelNames.clear();
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setStyle(Paint.Style.FILL);
        halo.setTypeface(paint.getTypeface());halo.setStrokeWidth(dp(3));halo.setColor(color(0xf2f8faf7,0xf2142737));
        float top=Math.max(0,f.labelTop),bottom=f.height-Math.max(0,f.labelBottom);
        for(int pass=0;pass<4;pass++)for(NativeMapScene.Shape s:visible){
            int want=pass==0?NativeMapScene.LABEL:pass==1?NativeMapScene.MOTORWAY:pass==2?NativeMapScene.MAIN:NativeMapScene.LOCAL;
            if(s.kind!=want||s.name.isEmpty()||!MapLayers.labelVisible(s.kind,f.zoom)||occupied.size()>=120)continue;
            float size=dp(want==NativeMapScene.LABEL?13:11);paint.setTextSize(size);halo.setTextSize(size);
            int i=(s.xy.length/4)*2;c.screen(s.xy[i],s.xy[i+1],screen);float x=screen[0],y=screen[1],w=paint.measureText(s.name);if(!Float.isFinite(x)||!Float.isFinite(y))continue;
            // Generous box so names keep breathing room; nothing under the search bar or bottom panel.
            RectF box=new RectF(x-w/2-dp(8),y-size-dp(4),x+w/2+dp(8),y+dp(6));
            if(box.left<0||box.right>f.width||box.top<top||box.bottom>bottom)continue;
            boolean collision=false;for(RectF other:occupied)if(RectF.intersects(other,box)){collision=true;break;}if(collision)continue;
            List<float[]> same=labelNames.get(s.name);if(MapLayers.repeatsNearby(x,y,same))continue;
            if(same==null){same=new ArrayList<>();labelNames.put(s.name,same);}same.add(new float[]{x,y});
            occupied.add(box);
            canvas.drawText(s.name,x-w/2,y,halo);
            paint.setColor(want==NativeMapScene.LABEL?color(0xff1f2d38,0xffeef4f8):color(0xff4a5862,0xffc9d6de));canvas.drawText(s.name,x-w/2,y,paint);
        }
    }
    private void stroke(Canvas c,Path p,int color,float width){if(p.isEmpty())return;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(width));c.drawPath(p,paint);}
    private void fill(Canvas c,Path p,int color){if(p.isEmpty())return;paint.setColor(color);paint.setStyle(Paint.Style.FILL);c.drawPath(p,paint);}
}
