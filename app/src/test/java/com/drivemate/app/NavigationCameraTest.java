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
        assertTrue(frame.zoom >= 14.2 && frame.zoom <= 19.0);
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
}
