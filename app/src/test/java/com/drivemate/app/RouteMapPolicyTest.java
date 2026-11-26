package com.drivemate.app;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class RouteMapPolicyTest {
    @Test public void sourceSelectionNeverTreatsIndependentAsTomTom(){
        assertFalse(RouteMapPolicy.useTomTomMap("TomTom",true));
        assertFalse(RouteMapPolicy.useTomTomMap("TomTom",false));
        assertFalse(RouteMapPolicy.useTomTomMap("DriveMate",true));
        assertFalse(RouteMapPolicy.useTomTomMap("DriveMate local preview",true));
        assertFalse(RouteMapPolicy.useTomTomMap(null,true));
        assertFalse(RouteMapPolicy.useTomTomVector("TomTom",true));
        assertFalse(RouteMapPolicy.useTomTomVector("DriveMate local preview",true));
        assertFalse(RouteMapPolicy.useTomTomVector(null,true));
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
    @Test public void hostedMapBoundsAreBoundedAndContainRoute(){
        GeoMath.Point a=new GeoMath.Point(53.4,-2.16),b=new GeoMath.Point(53.41,-2.17);
        double[] bounds=RouteMapPolicy.corridorBounds(Arrays.asList(a,b));assertNotNull(bounds);
        assertTrue(RouteMapPolicy.insideBounds(a,bounds));assertTrue(RouteMapPolicy.insideBounds(b,bounds));
        assertNull(RouteMapPolicy.corridorBounds(Arrays.asList(a,new GeoMath.Point(54,-2.16))));
        assertFalse(RouteMapPolicy.insideBounds(new GeoMath.Point(54,-2.16),bounds));
    }
    @Test public void expandedRegionalInsetCoversCheadleButNotWholeUk(){
        assertTrue(RouteMapPolicy.insideStockport(new GeoMath.Point(53.393,-2.216)));
        assertTrue(RouteMapPolicy.insideStockport(new GeoMath.Point(53.376,-2.202)));
        assertFalse(RouteMapPolicy.insideStockport(new GeoMath.Point(53.36,-2.216)));
        assertFalse(RouteMapPolicy.insideStockport(new GeoMath.Point(53.393,-2.25)));
    }
    @Test public void smallerDisplayMarginPreservesRouteAndReducesContext()throws Exception{
        GeoMath.Point a=new GeoMath.Point(53.405,-2.165),b=new GeoMath.Point(53.410,-2.171);
        double[] wide=DmUnifiedRegion.displayBounds(Arrays.asList(a,b),true,1);
        double[] narrow=DmUnifiedRegion.displayBounds(Arrays.asList(a,b),true,.2);
        assertTrue(RouteMapPolicy.insideBounds(a,narrow));
        assertTrue(RouteMapPolicy.insideBounds(b,narrow));
        assertTrue(narrow[2]-narrow[0]<wide[2]-wide[0]);
        assertTrue(narrow[3]-narrow[1]<wide[3]-wide[1]);
    }
    @Test public void localContextForLongRouteStaysWithinVerifiedRegion(){
        double[] region={-2.333,53.382,-2.152,53.443};
        GeoMath.Point start=new GeoMath.Point(53.383,-2.332);
        double[] b=DmUnifiedRegion.localBounds(start,region);
        assertTrue(RouteMapPolicy.insideBounds(start,b));
        assertEquals(region[0],b[0],1e-10);assertEquals(region[1],b[1],1e-10);
        assertTrue(b[2]-b[0]<=.008001);assertTrue(b[3]-b[1]<=.006001);
        assertFalse(RouteMapPolicy.insideBounds(new GeoMath.Point(53.4,-2.16),b));
    }
    @Test public void routeWidthIsBoundedAndZoomDependent(){
        assertEquals(4,RouteMapPolicy.rasterWidthDp(Double.NaN));
        assertTrue(RouteMapPolicy.rasterWidthDp(13)<RouteMapPolicy.rasterWidthDp(19));
        assertEquals(9,RouteMapPolicy.rasterWidthDp(25));
    }
}
