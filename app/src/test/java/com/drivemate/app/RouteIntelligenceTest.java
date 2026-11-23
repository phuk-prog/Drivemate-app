package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class RouteIntelligenceTest {
    @Test public void unknownAndUnrecommendedLanesAreNeverDisplayed() {
        RouteIntelligence.LaneSection noFollow = new RouteIntelligence.LaneSection(
                100, 120, Arrays.asList(new RouteIntelligence.Lane("LEFT", false)));
        RouteIntelligence.LaneSection unknown = new RouteIntelligence.LaneSection(
                120, 145, Arrays.asList(new RouteIntelligence.Lane("MYSTERY", true)));
        RouteIntelligence.LaneSection verified = new RouteIntelligence.LaneSection(
                230, 270, Arrays.asList(new RouteIntelligence.Lane("LEFT", false),
                        new RouteIntelligence.Lane("STRAIGHT", true)));
        assertFalse(noFollow.trusted());
        assertFalse(unknown.trusted());
        assertEquals("LANES  ↰   [↑]", verified.display());
        assertSame(verified, RouteIntelligence.approachingLanes(
                Arrays.asList(noFollow, unknown, verified), 20, 300));
        assertNull(RouteIntelligence.approachingLanes(
                Collections.singletonList(verified), 0, 150));
        // Once the car has crossed a lane-section end, old lane advice
        // must disappear rather than persist at the last junction.
        assertNull(RouteIntelligence.approachingLanes(
                Collections.singletonList(verified), 270, 300));
        assertEquals("⤺", new RouteIntelligence.Lane("LEFT_U_TURN", true).symbol());
        assertEquals("⤻", new RouteIntelligence.Lane("RIGHT_U_TURN", true).symbol());
    }

    @Test public void speedLimitRequiresStrongMatchingAndFreshRoute() {
        RouteIntelligence.SpeedSection limit = new RouteIntelligence.SpeedSection(40, 300, 48);
        assertSame(limit, RouteIntelligence.speedAt(
                Collections.singletonList(limit), 120, 8, 12, 1000));
        assertNull(RouteIntelligence.speedAt(
                Collections.singletonList(limit), 120, 30, 12, 1000));
        assertNull(RouteIntelligence.speedAt(
                Collections.singletonList(limit), 120, 5, 40, 1000));
        assertNull(RouteIntelligence.speedAt(
                Collections.singletonList(limit), 120, 5, 15, 500000));
        assertNull(RouteIntelligence.speedAt(
                Collections.singletonList(limit), 302, 5, 15, 1000));
        assertNull(RouteIntelligence.speedAt(
                Arrays.asList(limit, new RouteIntelligence.SpeedSection(40, 300, 80)),
                120, 5, 15, 1000)); // Two conflicting limits are unknown.
        assertTrue(RouteIntelligence.overspeed(17, limit, 5));
        assertFalse(RouteIntelligence.overspeed(13, limit, 5));
        assertFalse(RouteIntelligence.overspeed(40, null, 5));
    }

    @Test public void trafficIsAlreadyRouteLocatedAndDoesNotNeedGuesswork() {
        RouteIntelligence.TrafficSection roadworks =
                new RouteIntelligence.TrafficSection(400, 700, 120, "ROAD_WORK");
        RouteIntelligence.TrafficSection later =
                new RouteIntelligence.TrafficSection(900, 1200, 300, "JAM");
        assertEquals("Roadworks", roadworks.label());
        assertSame(roadworks, RouteIntelligence.nextTraffic(
                Arrays.asList(later, roadworks), 100, 800));
        assertSame(later, RouteIntelligence.nextTraffic(
                Arrays.asList(later, roadworks), 710, 800));
        assertNull(RouteIntelligence.nextTraffic(
                Collections.singletonList(roadworks), 710, 800));
        assertEquals("Reported traffic",
                new RouteIntelligence.TrafficSection(0, 50, 0, "UNRECOGNISED").label());
    }

    @Test public void routeChangeNeedsMaterialGeometryAndTimeBenefit() {
        GeoMath.Point a = new GeoMath.Point(51.5, -0.10);
        GeoMath.Point b = new GeoMath.Point(51.5, -0.09);
        GeoMath.Point c = new GeoMath.Point(51.51, -0.09);
        GeoMath.Point diverted = new GeoMath.Point(51.52, -0.11);
        assertTrue(RouteIntelligence.genuinelyDifferent(
                Arrays.asList(a, b, c), 0, Arrays.asList(a, diverted, c)));
        assertFalse(RouteIntelligence.genuinelyDifferent(
                Arrays.asList(a, b, c), 0, Arrays.asList(a, b, c)));
        assertEquals(240, RouteIntelligence.meaningfulSaving(1200, 960, true));
        assertEquals(0, RouteIntelligence.meaningfulSaving(1200, 1100, true));
        assertEquals(0, RouteIntelligence.meaningfulSaving(1200, 900, false));
    }
}
