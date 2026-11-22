package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class GeoMathTest {
    @Test public void distanceIsSymmetricAndZeroAtSamePoint() {
        GeoMath.Point a = new GeoMath.Point(53.38, -2.26);
        GeoMath.Point b = new GeoMath.Point(53.381, -2.26);
        assertEquals(0, GeoMath.distance(a, a), 0.0001);
        assertEquals(GeoMath.distance(a, b), GeoMath.distance(b, a), 0.001);
        assertTrue(GeoMath.distance(a, b) > 100);
    }

    @Test public void projectedProgressMeasuresAlongPolyline() {
        GeoMath.Point a = new GeoMath.Point(53.0, -2.0);
        GeoMath.Point b = new GeoMath.Point(53.01, -2.0);
        GeoMath.Point middle = new GeoMath.Point(53.005, -2.0);
        GeoMath.Progress p = GeoMath.project(Arrays.asList(a, b), middle);
        assertEquals(0, p.offRouteMetres, 0.2);
        assertEquals(p.totalMetres / 2, p.alongMetres, 2.0);
    }

    @Test public void offRoutePositionDoesNotAdvancePastDestination() {
        GeoMath.Point a = new GeoMath.Point(52.0, 0.0);
        GeoMath.Point b = new GeoMath.Point(52.01, 0.0);
        GeoMath.Point beyond = new GeoMath.Point(52.02, 0.0);
        GeoMath.Progress p = GeoMath.project(Arrays.asList(a, b), beyond);
        assertEquals(p.totalMetres, p.alongMetres, 1.0);
        assertTrue(p.offRouteMetres > 1000);
    }

    @Test public void emptyRouteIsHandled() {
        GeoMath.Progress p = GeoMath.project(Arrays.asList(), new GeoMath.Point(0, 0));
        assertTrue(Double.isInfinite(p.offRouteMetres));
    }

    @Test public void interpolatedRoadPointIsBetweenVertices() {
        GeoMath.Point a = new GeoMath.Point(53.0, -2.0);
        GeoMath.Point b = new GeoMath.Point(53.01, -2.0);
        double total = GeoMath.distance(a, b);
        GeoMath.Point midpoint = GeoMath.pointAtDistance(Arrays.asList(a, b), total / 2);
        assertEquals(53.005, midpoint.lat, 0.00001);
        assertEquals(-2.0, midpoint.lon, 0.00001);
    }

    @Test public void interpolationClampsOutsideRoute() {
        GeoMath.Point a = new GeoMath.Point(53.0, -2.0);
        GeoMath.Point b = new GeoMath.Point(53.01, -2.0);
        assertEquals(a.lat, GeoMath.pointAtDistance(Arrays.asList(a, b), -10).lat, 0);
        assertEquals(b.lat, GeoMath.pointAtDistance(Arrays.asList(a, b), 100000).lat, 0);
    }

    @Test public void bearingPointsTowardsEast() {
        assertEquals(90, GeoMath.bearingDegrees(
                new GeoMath.Point(53, -2.0),
                new GeoMath.Point(53, -1.99)), 0.5);
    }
}
