package com.drivemate.app;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class RouteRenderGeometryTest {
    @Test public void preservesAllSmallAndLargeCurves(){
        GeoMath.Point a=new GeoMath.Point(53,-2),b=new GeoMath.Point(53.00005,-1.99999),c=new GeoMath.Point(53.00010,-2),d=new GeoMath.Point(53.00020,-1.9998);
        List<GeoMath.Point> original=Arrays.asList(a,b,c,d);
        RouteRenderGeometry g=new RouteRenderGeometry(original);
        assertEquals(original,g.full());
        assertSame(b,g.full().get(1));
        List<GeoMath.Point> rest=g.remaining(GeoMath.distance(a,b)/2);
        assertEquals(4,rest.size());assertSame(b,rest.get(1));assertSame(c,rest.get(2));assertSame(d,rest.get(3));
        assertEquals(0,GeoMath.project(original,rest.get(0)).offRouteMetres,.01);
        assertTrue(g.remaining(g.length).isEmpty());
        assertEquals(original,g.remaining(0));
    }
    @Test public void sourceDisagreementMeasuredNotSnapped(){
        GeoMath.Point a=new GeoMath.Point(53,-2),b=new GeoMath.Point(53.0005,-1.9998),c=new GeoMath.Point(53.001,-2);
        RouteRenderGeometry g=new RouteRenderGeometry(Arrays.asList(a,b,c));
        double gap=RouteRenderGeometry.nearestRoadDistance(b,Collections.singletonList(Arrays.asList(a,c)));
        assertTrue(gap>10);
        assertSame(b,g.full().get(1));
        assertEquals(0,RouteRenderGeometry.nearestRoadDistance(b,Collections.singletonList(g.full())),.01);
    }
    @Test public void exactVertexAndDuplicateNeverCreateAChord(){
        GeoMath.Point a=new GeoMath.Point(53,-2),b=new GeoMath.Point(53.001,-2),c=new GeoMath.Point(53.001,-1.999);
        RouteRenderGeometry g=new RouteRenderGeometry(Arrays.asList(a,b,b,c));
        List<GeoMath.Point> rest=g.remaining(GeoMath.distance(a,b));
        assertEquals(2,rest.size());assertSame(b,rest.get(0));assertSame(c,rest.get(1));
    }
}
