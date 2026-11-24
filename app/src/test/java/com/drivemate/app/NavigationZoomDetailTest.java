package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class NavigationZoomDetailTest {
    private final List<GeoMath.Point> route = Arrays.asList(
            new GeoMath.Point(53.000, -2.000),
            new GeoMath.Point(53.015, -2.000),
            new GeoMath.Point(53.015, -1.990));

    @Test public void junctionModeActuallyChangesCloseUpAndPitch() {
        double progress = 850, nextTurn = 955;
        GeoMath.Point gps = route.get(0);
        NavigationCamera.Frame defaultCamera = NavigationCamera.calculate(
                route, gps, progress, nextTurn, 12, 1400, 290, 160);
        NavigationCamera.Frame detailCamera = NavigationCamera.calculate(
                route, gps, progress, nextTurn, 12, 1400, 290, 160, true);
        assertTrue("Detail setting must visibly zoom in", detailCamera.zoom
                > defaultCamera.zoom + 0.08);
        assertTrue("Detail camera must show a clearer overhead view",
                detailCamera.tiltDegrees < defaultCamera.tiltDegrees);
        assertTrue(detailCamera.tiltDegrees >= 17);
        assertTrue(detailCamera.zoom <= NavigationCamera.MAX_ZOOM);
        assertTrue(detailCamera.cameraCenterAlongMetres >= progress);
        assertTrue(detailCamera.cameraCenterAlongMetres <= nextTurn + 100);
    }

    @Test public void detailModeDoesNotAffectDistantNormalDriving() {
        GeoMath.Point gps = route.get(0);
        NavigationCamera.Frame normal = NavigationCamera.calculate(
                route, gps, 50, 800, 20, 1300, 300, 160);
        NavigationCamera.Frame detail = NavigationCamera.calculate(
                route, gps, 50, 800, 20, 1300, 300, 160, true);
        assertEquals(normal.zoom, detail.zoom, 0.00001);
        assertEquals(normal.tiltDegrees, detail.tiltDegrees, 0.00001);
        assertEquals(normal.cameraCenterAlongMetres, detail.cameraCenterAlongMetres,
                0.00001);
    }

    @Test public void motorwaySpeedPreservesRoadVisibilityOnCloseTurn() {
        NavigationCamera.Frame slow = NavigationCamera.calculate(
                route, route.get(0), 800, 900, 1, 1500, 250, 150, true);
        NavigationCamera.Frame fast = NavigationCamera.calculate(
                route, route.get(0), 800, 900, 32, 1500, 250, 150, true);
        assertTrue("Higher speed should not crop look-ahead by zooming tighter",
                fast.zoom < slow.zoom);
        assertTrue(fast.cameraCenterAlongMetres >= 800);
        assertTrue(fast.cameraCenterAlongMetres <= 900 + 180);
    }

    @Test public void junctionModeDoesNotTurnCarBeforeActualTurn() {
        double junction = GeoMath.distance(route.get(0), route.get(1));
        NavigationCamera.Frame frame = NavigationCamera.calculate(
                route, route.get(0), junction - 48, junction, 5,
                1300, 250, 180, true);
        assertTrue(frame.roadBearingDegrees < 9
                || frame.roadBearingDegrees > 351);
    }
}
