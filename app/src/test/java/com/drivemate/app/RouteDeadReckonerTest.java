package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class RouteDeadReckonerTest {
    /** A straight 5 km route due north from (53, -2). */
    private static List<GeoMath.Point> northRoute() {
        List<GeoMath.Point> path = new ArrayList<>();
        for (int i = 0; i <= 50; i++) path.add(new GeoMath.Point(53 + i * 0.0009, -2));
        return path;
    }

    private static RouteDeadReckoner reckoner() {
        RouteDeadReckoner r = new RouteDeadReckoner();
        r.setRoute(northRoute());
        return r;
    }

    private static final GeoMath.Point START = new GeoMath.Point(53.002, -2);

    @Test public void noEstimateBeforeAnyFix() {
        assertNull(reckoner().estimate(10_000));
    }

    @Test public void noEstimateForNormalFixSpacing() {
        RouteDeadReckoner r = reckoner();
        r.onFix(START, 5, 0, 20f, 0);
        assertNull(r.estimate(1000));
    }

    @Test public void followsRouteNorthAtFixSpeed() {
        RouteDeadReckoner r = reckoner();
        r.onFix(START, 5, 0, 20f, 0);
        RouteDeadReckoner.Estimate e = r.estimate(5000);
        assertNotNull(e);
        assertTrue(e.estimated);
        // 20 m/s for 5 s is about 100 m, i.e. about 0.0009 degrees of latitude.
        double metres = GeoMath.distance(START, e.point);
        assertEquals(100, metres, 3);
        assertEquals(0, e.bearingDegrees, 1.0);
    }

    @Test public void stationaryVehicleGivesNoEstimate() {
        RouteDeadReckoner r = reckoner();
        r.onFix(START, 5, 0, 0.5f, 0);
        assertNull(r.estimate(8000));
    }

    @Test public void openRoadBridgeStopsAfterThirtySeconds() {
        RouteDeadReckoner r = reckoner();
        r.onFix(START, 5, 0, 10f, 0);
        assertNotNull(r.estimate(29_000));
        assertNull(r.estimate(31_000));
    }

    @Test public void tunnelBridgeContinuesUntilTunnelEnd() {
        RouteDeadReckoner r = reckoner();
        // Tunnel from about 150 m to 900 m along the route.
        r.setTunnelSpans(Collections.singletonList(new double[] {150, 900}));
        GeoMath.Point inside = GeoMath.pointAtDistance(northRoute(), 200);
        r.onFix(inside, 5, 0, 20f, 0);
        RouteDeadReckoner.Estimate e = r.estimate(35_000);   // beyond the open-road limit
        assertNotNull("tunnel bridge outlives the open-road limit", e);
        assertTrue(e.inTunnel);
        assertTrue(e.alongMetres <= 900.5);
    }

    @Test public void holdsAtTunnelExit() {
        RouteDeadReckoner r = reckoner();
        r.setTunnelSpans(Collections.singletonList(new double[] {150, 400}));
        r.onFix(GeoMath.pointAtDistance(northRoute(), 200), 5, 0, 20f, 0);
        RouteDeadReckoner.Estimate e = r.estimate(60_000);
        // Uncertainty may already have given up by now; if not, it must be held at the exit.
        if (e != null) assertEquals(400, e.alongMetres, 0.5);
    }

    @Test public void neverPassesAManoeuvre() {
        RouteDeadReckoner r = reckoner();
        r.setHoldPoints(new double[] {600});
        r.onFix(START, 5, 0, 25f, 0);
        RouteDeadReckoner.Estimate e = r.estimate(25_000);
        assertNotNull(e);
        double start = GeoMath.project(northRoute(), START).alongMetres;
        assertTrue(e.alongMetres >= start);
        assertTrue("stops 30 m before the manoeuvre", e.alongMetres <= 600 - LocationThresholds.MANOEUVRE_HOLD_METRES + 0.5);
    }

    @Test public void manoeuvreBehindTheAnchorDoesNotBlock() {
        RouteDeadReckoner r = reckoner();
        r.setHoldPoints(new double[] {100});
        r.onFix(GeoMath.pointAtDistance(northRoute(), 500), 5, 0, 20f, 0);
        RouteDeadReckoner.Estimate e = r.estimate(5000);
        assertNotNull(e);
        assertTrue(e.alongMetres > 500);
    }

    @Test public void speedEasesTowardExpectedSpeed() {
        RouteDeadReckoner fast = reckoner(), slow = reckoner();
        fast.onFix(START, 5, 0, 30f, 0);      // holds 30 m/s
        slow.onFix(START, 5, 0, 30f, 10);     // eases toward 10 m/s
        RouteDeadReckoner.Estimate a = fast.estimate(20_000), b = slow.estimate(20_000);
        assertTrue(b.alongMetres < a.alongMetres - 100);
    }

    @Test public void uncertaintyGrowsOverTime() {
        RouteDeadReckoner r = reckoner();
        r.onFix(START, 8, 0, 15f, 0);
        double early = r.estimate(3000).uncertaintyMetres;
        double later = r.estimate(15_000).uncertaintyMetres;
        assertTrue(later > early);
        assertTrue(early >= 8);
    }

    @Test public void givesUpWhenUncertaintyTooLarge() {
        RouteDeadReckoner r = reckoner();
        r.onFix(START, 240, 0, 15f, 0);
        assertNull(r.estimate(8000));
    }

    @Test public void fixFarFromRouteIsIgnored() {
        RouteDeadReckoner r = reckoner();
        r.onFix(new GeoMath.Point(53.002, -2.01), 5, 0, 20f, 0);   // ~660 m off the route
        assertNull(r.estimate(5000));
    }

    @Test public void clampsAtRouteEnd() {
        RouteDeadReckoner r = new RouteDeadReckoner();
        List<GeoMath.Point> shortRoute = new ArrayList<>();
        shortRoute.add(new GeoMath.Point(53, -2));
        shortRoute.add(new GeoMath.Point(53.001, -2));
        r.setRoute(shortRoute);
        r.onFix(new GeoMath.Point(53.0005, -2), 5, 0, 30f, 0);
        RouteDeadReckoner.Estimate e = r.estimate(10_000);
        assertNotNull(e);
        assertTrue(e.alongMetres <= GeoMath.length(shortRoute) + 0.01);
    }

    @Test public void resetAndSetRouteClearAnchor() {
        RouteDeadReckoner r = reckoner();
        r.onFix(START, 5, 0, 20f, 0);
        r.reset();
        assertNull(r.estimate(5000));
        r.onFix(START, 5, 0, 20f, 0);
        r.setRoute(northRoute());
        assertNull(r.estimate(5000));
    }
}
