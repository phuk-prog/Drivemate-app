package com.drivemate.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReroutePolicyTest {
    @Test public void fortyMetresOffWithGoodGpsCountsAsOffRoute() {
        assertTrue(ReroutePolicy.offRoute(45, 8));
        assertFalse(ReroutePolicy.offRoute(35, 8));
    }

    @Test public void poorGpsNeedsMoreDistanceAndVeryPoorGpsNeverCounts() {
        assertFalse(ReroutePolicy.offRoute(55, 40));   // threshold 60 m at 40 m accuracy
        assertTrue(ReroutePolicy.offRoute(65, 40));
        assertFalse(ReroutePolicy.offRoute(500, 80));  // too inaccurate to trust
        assertFalse(ReroutePolicy.offRoute(500, Float.NaN));
    }

    @Test public void confirmsAfterFourSecondsMovingOrEightStopped() {
        assertFalse(ReroutePolicy.confirmed(10_000, 13_000, 10));
        assertTrue(ReroutePolicy.confirmed(10_000, 14_000, 10));
        assertFalse(ReroutePolicy.confirmed(10_000, 15_000, 1));
        assertTrue(ReroutePolicy.confirmed(10_000, 18_000, 1));
        assertFalse(ReroutePolicy.confirmed(0, 99_000, 10));
    }

    @Test public void rateLimitsRequests() {
        assertTrue(ReroutePolicy.mayRequest(100_000, 70_000, 80_000));
        assertFalse(ReroutePolicy.mayRequest(100_000, 90_000, 70_000));
        assertFalse(ReroutePolicy.mayRequest(100_000, 70_000, 90_000));
    }

    @Test public void wrongWayConfirmsInTwoSecondsWhenMoving() {
        assertTrue(ReroutePolicy.confirmed(1000, 3000, 12f, true));
        assertFalse(ReroutePolicy.confirmed(1000, 2500, 12f, true));
        // Not moving: the quick rule does not apply.
        assertFalse(ReroutePolicy.confirmed(1000, 3000, 1f, true));
        // The three-argument form is unchanged.
        assertFalse(ReroutePolicy.confirmed(1000, 3000, 12f));
    }

    @Test public void wrongWayDetection() {
        assertTrue(ReroutePolicy.wrongWay(0, 180, 10f));
        assertTrue(ReroutePolicy.wrongWay(10, 200, 10f));
        assertFalse(ReroutePolicy.wrongWay(0, 90, 10f));
        assertFalse(ReroutePolicy.wrongWay(0, 180, 1f));
        assertFalse(ReroutePolicy.wrongWay(Double.NaN, 180, 10f));
        assertFalse(ReroutePolicy.wrongWay(0, Double.NaN, 10f));
    }

    @Test public void retryBacksOffAfterFailures() {
        assertEquals(20_000, ReroutePolicy.retryGapMs(0));
        assertEquals(40_000, ReroutePolicy.retryGapMs(1));
        assertEquals(80_000, ReroutePolicy.retryGapMs(2));
        assertEquals(120_000, ReroutePolicy.retryGapMs(3));
        assertEquals(120_000, ReroutePolicy.retryGapMs(30));
    }

    @Test public void mayRequestRespectsBackoff() {
        assertTrue(ReroutePolicy.mayRequest(100_000, 0, 0, 0));
        assertFalse(ReroutePolicy.mayRequest(30_000, 0, 0, 1));    // needs 40 s
        assertTrue(ReroutePolicy.mayRequest(41_000, 0, 0, 1));
    }
}
