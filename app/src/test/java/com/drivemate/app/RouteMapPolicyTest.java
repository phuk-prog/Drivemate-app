package com.drivemate.app;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class RouteMapPolicyTest {
    @Test public void sourceSelectionNeverTreatsIndependentAsTomTom(){
        assertTrue(RouteMapPolicy.useTomTomMap("TomTom",true));
        assertFalse(RouteMapPolicy.useTomTomMap("TomTom",false));
        assertFalse(RouteMapPolicy.useTomTomMap("DriveMate",true));
        assertFalse(RouteMapPolicy.useTomTomMap("DriveMate local preview",true));
        assertFalse(RouteMapPolicy.useTomTomMap(null,true));
    }
    @Test public void exactGeometryRejectsMissingBendsAndDifferentRoad(){
        GeoMath.Point a=new GeoMath.Point(53.4,-2.16),b=new GeoMath.Point(53.40001,-2.16002),c=new GeoMath.Point(53.401,-2.161);
        assertTrue(RouteMapPolicy.samePoints(Arrays.asList(a,b,c),Arrays.asList(a,b,c)));
        assertFalse(RouteMapPolicy.samePoints(Arrays.asList(a,b,c),Arrays.asList(a,c)));
        assertFalse(RouteMapPolicy.samePoints(Arrays.asList(a,b,c),Arrays.asList(a,new GeoMath.Point(53.40001,-2.16003),c)));
        assertTrue(RouteMapPolicy.insideStockport(Arrays.asList(a,b,c)));
        assertFalse(RouteMapPolicy.insideStockport(Arrays.asList(a,new GeoMath.Point(53.50,-2.16))));
    }
    @Test public void partialSegmentsNeverCutAcrossBends(){
        GeoMath.Point a=new GeoMath.Point(53.4,-2.16),b=new GeoMath.Point(53.4001,-2.16),c=new GeoMath.Point(53.4001,-2.1599);
        assertTrue(RouteMapPolicy.onSameSegment(a,b,new GeoMath.Point(53.40002,-2.16),b));
        assertFalse(RouteMapPolicy.onSameSegment(a,b,a,c));
        assertFalse(RouteMapPolicy.onSameSegment(b,c,a,c));
    }
    @Test public void routeWidthIsBoundedAndZoomDependent(){
        assertEquals(4,RouteMapPolicy.rasterWidthDp(Double.NaN));
        assertTrue(RouteMapPolicy.rasterWidthDp(13)<RouteMapPolicy.rasterWidthDp(19));
        assertEquals(9,RouteMapPolicy.rasterWidthDp(25));
    }
}
