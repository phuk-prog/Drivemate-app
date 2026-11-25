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
        assertTrue(close.zoom <= NavigationCamera.MAX_ZOOM);
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
    @Test public void providesExactRouteCenterOffsetAcrossBends() {
        List<GeoMath.Point> bend = Arrays.asList(
                new GeoMath.Point(53.0, -2.0),
                new GeoMath.Point(53.001, -2.0),
                new GeoMath.Point(53.001, -1.999),
                new GeoMath.Point(53.0, -1.999));
        NavigationCamera.Frame frame = NavigationCamera.calculate(
                bend, bend.get(1), 120, 210, 8, 1300, 260, 110);
        RouteMotionIndex indexed = new RouteMotionIndex(bend);
        GeoMath.Point expected = indexed.pointAt(frame.cameraCenterAlongMetres);
        assertEquals(expected.lat, frame.center.lat, 0.0000001);
        assertEquals(expected.lon, frame.center.lon, 0.0000001);
        assertTrue(frame.cameraCenterAlongMetres >= 120);
    }

    @Test public void speedExpandsTheLookAheadAndReducesZoom() {
        GeoMath.Point gps = north.get(0);
        NavigationCamera.Frame stopped = NavigationCamera.calculate(
                north, gps, 0, 80, 0, 1300, 180, 260);
        NavigationCamera.Frame fast = NavigationCamera.calculate(
                north, gps, 0, 80, 30, 1300, 180, 260);
        assertTrue(stopped.zoom > fast.zoom);
    }

    @Test public void tiltedApproachZoomsCloserAndBecomesMoreOverheadAtTurn() {
        GeoMath.Point driver = north.get(0);
        NavigationCamera.Frame far = NavigationCamera.calculate(
                north, driver, 0, 550, 5, 1536, 480, 120);
        NavigationCamera.Frame near = NavigationCamera.calculate(
                north, driver, 450, 550, 5, 1536, 480, 120);
        assertTrue(near.zoom > far.zoom);
        assertTrue(near.tiltDegrees < far.tiltDegrees);
        assertTrue(near.tiltDegrees >= 28);
        assertTrue(far.tiltDegrees <= 48);
    }

    @Test public void largeGuidanceCardStillKeepsVehicleAndJunctionAhead() {
        NavigationCamera.Frame frame = NavigationCamera.calculate(
                north, north.get(0), 150, 670, 2, 1400, 475, 140);
        assertTrue(frame.cameraCenterAlongMetres > 150);
        assertTrue(frame.cameraCenterAlongMetres < 670 + 130);
        assertTrue(frame.zoom >= NavigationCamera.MIN_ZOOM
                && frame.zoom <= NavigationCamera.MAX_ZOOM);
    }

    @Test public void approachHeadingDoesNotPrematurelyTurnOntoExit() {
        List<GeoMath.Point> rightAngle = Arrays.asList(
                new GeoMath.Point(53.0, -2.0),
                new GeoMath.Point(53.001, -2.0),
                new GeoMath.Point(53.001, -1.998));
        double offset = GeoMath.distance(rightAngle.get(0), rightAngle.get(1));
        NavigationCamera.Frame frame = NavigationCamera.calculate(
                rightAngle, rightAngle.get(0), offset - 40, offset, 4,
                1300, 360, 130);
        assertTrue(frame.roadBearingDegrees < 8 ||
                frame.roadBearingDegrees > 352);
    }

    @Test public void urbanSpeedZoomStillChangesBetween300And100Yards() {
        GeoMath.Point driver = north.get(0);
        NavigationCamera.Frame threeHundred = NavigationCamera.calculate(
                north, driver, 0, 275, 13, 1400, 350, 120);
        NavigationCamera.Frame oneHundred = NavigationCamera.calculate(
                north, driver, 175, 275, 13, 1400, 350, 120);
        assertTrue(oneHundred.zoom > threeHundred.zoom + 0.1);
    }

    @Test public void tinyUsableViewportUsesLowerTiltAndFiniteZoom() {
        NavigationCamera.Frame frame = NavigationCamera.calculate(north, north.get(0), 0, 100, Float.NaN, 600, 300, 250);
        assertTrue(Double.isFinite(frame.zoom));
        assertTrue(frame.tiltDegrees <= 20);
        assertTrue(frame.cameraCenterAlongMetres >= 0);
    }

    @Test public void convertsRasterZoomToMapLibreZoom() {
        // 512 px tiles in dp: one level lower at density 1, plus log2(density).
        assertEquals(15.0, NavigationCamera.toMapLibreZoom(16.0, 1.0), 1e-9);
        assertEquals(14.0, NavigationCamera.toMapLibreZoom(16.0, 2.0), 1e-9);
        assertEquals(16.0 - 1 - Math.log(2.625) / Math.log(2),
                NavigationCamera.toMapLibreZoom(16.0, 2.625), 1e-9);
        // A bad density never produces a wild camera.
        assertEquals(15.0, NavigationCamera.toMapLibreZoom(16.0, 0), 1e-9);
        assertEquals(15.0, NavigationCamera.toMapLibreZoom(16.0, Double.NaN), 1e-9);
    }

    @Test public void distantNextTurnDoesNotZoomTheMapOut() {
        // Owner report: 320 yd from the next turn at walking pace, the map
        // zoomed far out to fit the whole approach. The cruise view is now
        // speed-based, so a far turn gives the same close view as a mid one.
        GeoMath.Point gps = north.get(0);
        NavigationCamera.Frame farTurn = NavigationCamera.calculate(
                north, gps, 0, 1800, 1, 1400, 400, 300);
        NavigationCamera.Frame midTurn = NavigationCamera.calculate(
                north, gps, 0, 300, 1, 1400, 400, 300);
        // Only camera tilt (which also eases with distance) differs slightly;
        // the old behaviour was about 3 zoom levels further out for the far turn.
        assertEquals(farTurn.zoom, midTurn.zoom, 0.1);
        // About 110 m ahead on a ~700 px view: street level (~18.5). The old
        // logic fitted the whole 1.8 km approach, about 14.9.
        assertTrue(farTurn.zoom > 18.0);
    }
}
