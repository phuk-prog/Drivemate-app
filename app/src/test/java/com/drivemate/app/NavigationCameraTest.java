package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class NavigationCameraTest {
    private final List<GeoMath.Point> north = Arrays.asList(
            new GeoMath.Point(53.0, -2.0),
            new GeoMath.Point(53.02, -2.0));
    @Test public void departureWithZeroOffsetStillFacesApproachRoad(){
        List<GeoMath.Point> east=Arrays.asList(new GeoMath.Point(53,-2),new GeoMath.Point(53,-1.98));
        NavigationCamera.Frame frame=NavigationCamera.calculate(east,east.get(0),0,0,0,1300,220,140,true);
        assertEquals(90,frame.roadBearingDegrees,1.0);
    }
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
        assertTrue(far.tiltDegrees <= NavigationCamera.MAX_TILT);
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

    @Test public void distantTurnDoesNotFitTheEntireJourney(){
        GeoMath.Point gps=north.get(0);
        NavigationCamera.Frame far=NavigationCamera.calculate(north,gps,0,1800,1,1400,400,300);
        NavigationCamera.Frame mid=NavigationCamera.calculate(north,gps,0,300,1,1400,400,300);
        NavigationCamera.Frame close=NavigationCamera.calculate(north,gps,0,200,1,1400,400,300);
        assertTrue(far.zoom>16);
        // A turn 300 m away no longer widens the cruise view; one just out of view may.
        assertEquals(far.zoom,mid.zoom,1e-9);
        assertTrue(close.zoom<far.zoom);
        assertTrue(far.cameraCenterAlongMetres<400);
    }
    @Test public void cruiseViewMatchesSpeed(){
        assertEquals(130,NavigationCamera.cruiseHorizon(0),1e-9);
        assertEquals(234,NavigationCamera.cruiseHorizon(13.41),1);   // 30 mph
        assertEquals(386,NavigationCamera.cruiseHorizon(31.29),1);   // 70 mph
        assertEquals(600,NavigationCamera.cruiseHorizon(200),1e-9);
    }
    @Test public void forwardCameraHasGreaterLeadAtUrbanApproach(){
        NavigationCamera.Frame f=NavigationCamera.calculate(north,north.get(0),0,384,1.3f,1450,230,155);
        assertTrue(f.cameraCenterAlongMetres>25);
        assertTrue(f.tiltDegrees<=NavigationCamera.MAX_TILT);
        assertTrue(f.cameraCenterAlongMetres<384);
    }

    @Test public void carIsPlacedAboveTheBottomPanelAndSpeedCluster(){
        // 2400 px phone: 620 px turn card, 700 px panel + speed cluster reserved at the bottom.
        int screen=2400,header=620,bottom=700;
        NavigationCamera.Frame f=NavigationCamera.calculate(north,north.get(0),0,1500,12,screen,header,bottom);
        double view=screen-header-bottom;
        double carY=header+view*NavigationCamera.CAR_SCREEN_FRACTION;
        assertTrue("car must sit above the reserved bottom area",carY<screen-bottom);
        // The centre is ahead of the car so that, drawn at the tilted anchor, the car lands at carY.
        double anchor=screen*NativeMapProjection.TILTED_ANCHOR;
        assertTrue(f.tiltDegrees>0);
        double drawnCarY=Math.max(carY,anchor);   // centre never behind the car
        assertTrue(drawnCarY<screen-bottom);
        // Old placement put the car about 470 px below the anchor: under the panel.
        assertTrue(anchor+470>screen-bottom);
    }
}
