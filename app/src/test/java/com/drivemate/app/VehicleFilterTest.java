package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class VehicleFilterTest {
    @Test public void filtersSmallJitterWithoutHidingLargeTravel() {
        VehicleFilter filter = new VehicleFilter();
        GeoMath.Point a = new GeoMath.Point(53, -2);
        GeoMath.Point first = filter.apply(a, 1000, 5, 0);
        GeoMath.Point nearby = filter.apply(new GeoMath.Point(53.00002,-2),2000,5,0);
        assertEquals(a.lat, first.lat, 0.00000001);
        assertTrue(nearby.lat > a.lat && nearby.lat < 53.00002);
        GeoMath.Point far = filter.apply(new GeoMath.Point(53.01,-2),3000,5,15);
        assertEquals(53.01,far.lat, 0.000001);
        filter.reset();
        assertEquals(53.02,filter.apply(new GeoMath.Point(53.02,-2),4000,5,0).lat,0);
    }
}
