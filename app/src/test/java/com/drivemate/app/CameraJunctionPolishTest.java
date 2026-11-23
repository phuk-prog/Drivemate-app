package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Pure geometry tests; no GPS permissions, tile key or Android device needed. */
public final class CameraJunctionPolishTest {
    private final List<GeoMath.Point> north = Arrays.asList(
            new GeoMath.Point(53.0, -2.0), new GeoMath.Point(53.02, -2.0));

    private NavigationCamera.Frame view(double progress, double turn,
                                         double second, float speed,
                                         boolean closeZoom, boolean complex) {
        return NavigationCamera.calculate(north, north.get(0), progress,
                turn, second, speed, 1400, 340, 150, closeZoom, complex);
    }

    @Test public void optionalJunctionDetailActuallyZoomsAtUrbanSpeeds() {
        NavigationCamera.Frame normal = view(380, 500, Double.NaN, 12, false, false);
        NavigationCamera.Frame detailed = view(380, 500, Double.NaN, 12, true, false);
        assertTrue("Junction detail toggle must visibly change zoom",
                detailed.zoom > normal.zoom + 0.12);
        assertTrue(detailed.tiltDegrees < normal.tiltDegrees);
        assertTrue(detailed.cameraCenterAlongMetres > 380);
        assertTrue(detailed.zoom <= 19.0);
    }

    @Test public void junctionDetailDoesNotCropMotorwayLookAhead() {
        NavigationCamera.Frame motorway = view(380, 500, Double.NaN, 30, false, false);
        NavigationCamera.Frame detailed = view(380, 500, Double.NaN, 30, true, false);
        assertEquals(motorway.zoom, detailed.zoom, 0.00001);
        assertEquals(motorway.cameraCenterAlongMetres,
                detailed.cameraCenterAlongMetres, 0.00001);
    }

    @Test public void closelyFollowingTurnForcesEnoughRoomForBoth() {
        NavigationCamera.Frame one = view(380, 500, Double.NaN, 10, true, false);
        NavigationCamera.Frame two = view(380, 500, 665, 10, true, false);
        assertTrue("Two turns must be fitted instead of cropping the second",
                two.zoom < one.zoom);
        assertTrue(two.cameraCenterAlongMetres <= 665 + 150);
    }

    @Test public void verifiedComplexJunctionEasesTowardsOverhead() {
        NavigationCamera.Frame ordinary = view(450, 510, Double.NaN, 10, false, false);
        NavigationCamera.Frame junction = view(450, 510, Double.NaN, 10, false, true);
        assertTrue(junction.tiltDegrees < ordinary.tiltDegrees);
        assertTrue(junction.tiltDegrees >= 20);
    }

    @Test public void directionRemainsOnApproachRoadThreeMetresBeforeRightTurn() {
        List<GeoMath.Point> route = Arrays.asList(
                new GeoMath.Point(53.0, -2.0),
                new GeoMath.Point(53.001, -2.0),
                new GeoMath.Point(53.001, -1.998));
        double atTurn = GeoMath.distance(route.get(0), route.get(1));
        NavigationCamera.Frame frame = NavigationCamera.calculate(
                route, route.get(0), atTurn - 3, atTurn, Double.NaN,
                5, 1400, 300, 140, true, true);
        assertTrue("Camera must not rotate onto unconfirmed exit",
                frame.roadBearingDegrees < 18 || frame.roadBearingDegrees > 342);
    }

    @Test public void displayedCarArrowAlsoWaitsForConfirmedTurn() {
        List<GeoMath.Point> route = Arrays.asList(
                new GeoMath.Point(53.0, -2.0),
                new GeoMath.Point(53.001, -2.0),
                new GeoMath.Point(53.001, -1.998));
        RouteMotionIndex index = new RouteMotionIndex(route);
        double atTurn = GeoMath.distance(route.get(0), route.get(1));
        double progress = atTurn - 4;
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        NavigationCamera.Frame target = new NavigationCamera.Frame(
                index.pointAt(progress + 2), 17, 0, 4, progress + 2, 25);
        motion.onGps(index.pointAt(progress), progress, 0, 5, 4, 4,
                target, false, 1000);
        SmoothFollowCamera.Frame frame = motion.frame(1000);
        assertTrue("Car arrow must remain on approach road",
                frame.travelBearing < 18 || frame.travelBearing > 342);
    }
}
