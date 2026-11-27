package com.drivemate.app;
import org.junit.Test;
import java.util.*;
import org.json.*;
import static org.junit.Assert.*;
public class NativeMapRenderingTest {
    @Test public void projectionRoundTripsAndKeepsBearingUp(){
        NativeMapProjection.Camera c=new NativeMapProjection.Camera(53.4,-2.16,17,90,45,1000,800);
        float[] out=new float[2];c.screen(NativeMapProjection.x(-2.159),NativeMapProjection.y(53.4),out);
        assertEquals(c.anchorX,out[0],.01);assertTrue(out[1]<c.anchorY);
        GeoMath.Point p=c.unproject(out[0],out[1]);assertEquals(53.4,p.lat,1e-7);assertEquals(-2.159,p.lon,1e-7);
    }
    @Test public void perspectiveNearerObjectsAreLargerAndInverseIsStable(){
        NativeMapProjection.Camera c=new NativeMapProjection.Camera(53.4,-2.16,17,0,45,1000,800);
        double d=80/c.scale;float[] near=new float[2],far=new float[2];
        c.screen(c.cx+d,c.cy+4*d,near);c.screen(c.cx+d,c.cy-4*d,far);
        assertTrue(near[0]-c.anchorX>far[0]-c.anchorX);
        GeoMath.Point p=c.unproject(near[0],near[1]);
        assertEquals(NativeMapProjection.lon(c.cx+d),p.lon,1e-6);
        assertEquals(NativeMapProjection.lat(c.cy+4*d),p.lat,1e-6);
    }
    @Test public void exactSourceWaysRequired()throws Exception{
        JSONArray coords=new JSONArray().put(new JSONArray().put(53400000).put(-2160000)).put(new JSONArray().put(53400100).put(-2160000));
        JSONArray row=new JSONArray().put(5).put(new JSONObject().put("highway","residential")).put(coords).put(new JSONArray().put(10).put(11));
        List<GeoMath.Point> p=Arrays.asList(new GeoMath.Point(53.4,-2.16),new GeoMath.Point(53.4001,-2.16));
        assertEquals(1,NativeMapScene.create(Collections.singletonList(row),p,Collections.singletonList(5L),Arrays.asList(10L,11L),new double[]{-2.2,53.3,-2.1,53.5}).checkedEdges);
        try{NativeMapScene.create(Collections.singletonList(row),p,Collections.singletonList(5L),Arrays.asList(10L,12L),new double[]{-2.2,53.3,-2.1,53.5});fail("wrong source node accepted");}catch(java.io.IOException expected){}
    }
}
