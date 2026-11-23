package com.drivemate.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class LocationRatePolicyTest {
    @Test public void navigatingUsesFastestRate() {
        assertEquals(0L, LocationRatePolicy.gpsIntervalMs(true));
        assertEquals(0f, LocationRatePolicy.minDistanceMetres(true), 0f);
        assertEquals(0L, LocationRatePolicy.networkIntervalMs(true));
    }

    @Test public void idleIsLighterThanNavigating() {
        assertTrue(LocationRatePolicy.gpsIntervalMs(false) > LocationRatePolicy.gpsIntervalMs(true));
        assertTrue(LocationRatePolicy.minDistanceMetres(false) > 0f);
        assertTrue(LocationRatePolicy.networkIntervalMs(false)
                >= LocationRatePolicy.gpsIntervalMs(false));
    }
}
