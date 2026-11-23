package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class RouteMotionIndexTest {
    @Test public void indexesLongRouteWithoutScanningForEveryAnimationFrame() {
        RouteMotionIndex route = new RouteMotionIndex(Arrays.asList(
                new GeoMath.Point(53.0,-2.0),
                new GeoMath.Point(53.001,-2.0),
                new GeoMath.Point(53.002,-2.0)));
        assertTrue(route.lengthMetres > 210);
        assertEquals(53.001,route.pointAt(route.lengthMetres / 2).lat, 0.00005);
        assertEquals(0, route.bearingAt(35,50), 1.5);
        assertEquals(53.002, route.pointAt(route.lengthMetres + 200).lat, 0.000001);
        assertEquals(53.0,route.pointAt(-100).lat, 0.000001);
    }

    @Test public void headingFollowsBendsAndDuplicateVertices() {
        RouteMotionIndex road = new RouteMotionIndex(Arrays.asList(
                new GeoMath.Point(53.0,-2.0),
                new GeoMath.Point(53.001,-2.0),
                new GeoMath.Point(53.001,-2.0),
                new GeoMath.Point(53.001,-1.999)));
        assertEquals(0,road.bearingAt(12,25),3);
        assertTrue(road.bearingAt(120,40)>35);
    }
}
