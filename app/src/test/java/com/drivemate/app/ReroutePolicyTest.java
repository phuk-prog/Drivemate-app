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
}
