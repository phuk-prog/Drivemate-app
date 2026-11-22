package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class NavigationCameraTest {
    private final List<GeoMath.Point> north = Arrays.asList(
            new GeoMath.Point(53.0, -2.0),
            new GeoMath.Point(53.02, -2.0));
    @Test public void zoomsInAsNextJunctionGetsCloser() {
        GeoMath.Point vehicle = new GeoMath.Point(53.005, -2.0);
        NavigationCamera.Frame distant = NavigationCamera.calculate(
                north, vehicle, 100, 850, 2, 1400, 180, 260);
        NavigationCamera.Frame close = NavigationCamera.calculate(
                north, vehicle, 100, 125, 2, 1400, 180, 260);
        assertTrue(close.zoom > distant.zoom);
        assertTrue(close.zoom <= 19.0);
        assertTrue(distant.zoom >= 14.2);
    }
    @Test public void cameraLooksAheadWithoutJumpingBeyondRoute() {
        GeoMath.Point vehicle = north.get(0);
        NavigationCamera.Frame frame = NavigationCamera.calculate(
                north, vehicle, 0, 80, 0, 1200, 170, 290);
        assertTrue(frame.center.lat > vehicle.lat);
        assertTrue(frame.center.lat < north.get(1).lat);
        assertEquals(0, frame.roadBearingDegrees, 1.0);
    }
    @Test public void headingCanFollowEastboundRoute() {
        List<GeoMath.Point> east = Arrays.asList(
                new GeoMath.Point(53, -2.0), new GeoMath.Point(53, -1.98));
        NavigationCamera.Frame frame = NavigationCamera.calculate(
                east, east.get(0), 0, 100, 1, 1200, 180, 260);
        assertEquals(90.0, frame.roadBearingDegrees, 1);
    }
    @Test public void speedExpandsTheLookAheadAndReducesZoom() {
        GeoMath.Point gps = north.get(0);
        NavigationCamera.Frame stopped = NavigationCamera.calculate(
                north, gps, 0, 80, 0, 1300, 180, 260);
        NavigationCamera.Frame fast = NavigationCamera.calculate(
                north, gps, 0, 80, 30, 1300, 180, 260);
        assertTrue(stopped.zoom > fast.zoom);
    }
}
