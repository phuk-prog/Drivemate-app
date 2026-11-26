package com.drivemate.app;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class NativeRouteIntelligenceTest {
    @Test public void sameNodeAndCoordinatesRequired()throws Exception{
        List<GeoMath.Point> p=Arrays.asList(new GeoMath.Point(53.4,-2.2),new GeoMath.Point(53.401,-2.2),new GeoMath.Point(53.402,-2.2));
        JSONArray data=new JSONArray().put(new JSONArray().put(2).put(53401000).put(-2200000).put(new JSONObject().put("highway","traffic_signals")))
            .put(new JSONArray().put(99).put(53401000).put(-2200000).put(new JSONObject().put("highway","traffic_signals")))
            .put(new JSONArray().put(3).put(53402001).put(-2200000).put(new JSONObject().put("highway","traffic_signals")));
        NativeRouteIntelligence.Result result=NativeRouteIntelligence.match(p,Arrays.asList(1L,2L,3L),Arrays.asList(5L,5L),data,new JSONArray());
        assertEquals(1,result.events.size());assertEquals(2,result.events.get(0).id);assertEquals(GeoMath.distance(p.get(0),p.get(1)),result.events.get(0).offset,.01);
    }
    @Test public void lineCrossingNearPlaneClipsInsteadOfDisappearing(){
        NativeMapProjection.Camera c=new NativeMapProjection.Camera(53.4,-2.2,17,0,45,800,600);
        float[] a=new float[2],b=new float[2];
        assertTrue(c.segment(c.cx,c.cy,c.cx,c.cy+3000/c.scale,a,b));
        assertTrue(Float.isFinite(a[0])&&Float.isFinite(b[1]));
    }
}
