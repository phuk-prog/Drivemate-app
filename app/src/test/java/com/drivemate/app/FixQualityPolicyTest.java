package com.drivemate.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class FixQualityPolicyTest {
    private static FixQualityPolicy.Sample gps(double lat, double lon, long t, float acc) {
        return new FixQualityPolicy.Sample(lat, lon, t, acc, true);
    }

    private static FixQualityPolicy.Sample net(double lat, double lon, long t, float acc) {
        return new FixQualityPolicy.Sample(lat, lon, t, acc, false);
    }

    @Test public void acceptsGoodFirstFixAndRejectsPoorFirstFix() {
        assertTrue(FixQualityPolicy.accept(null, gps(53, -2, 1000, 8)));
        assertFalse(FixQualityPolicy.accept(null, gps(53, -2, 1000, 250)));
    }

    @Test public void rejectsInvalidCoordinatesAndAccuracy() {
        assertFalse(FixQualityPolicy.accept(null, gps(95, -2, 1000, 5)));
        assertFalse(FixQualityPolicy.accept(null, gps(Double.NaN, -2, 1000, 5)));
        assertFalse(FixQualityPolicy.accept(null, gps(53, -2, 1000, Float.NaN)));
        assertFalse(FixQualityPolicy.accept(null, gps(53, -2, 1000, -1)));
        assertFalse(FixQualityPolicy.accept(null, null));
    }

    @Test public void rejectsStaleAndDuplicateFixes() {
        FixQualityPolicy.Sample prev = gps(53, -2, 5000, 5);
        assertFalse(FixQualityPolicy.accept(prev, gps(53, -2, 5000, 5)));
        assertFalse(FixQualityPolicy.accept(prev, gps(53, -2, 4000, 5)));
    }

    @Test public void networkFixIgnoredWhileGpsIsFresh() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        // 3 s later: a network fix must not override fresh GPS, even if accurate.
        assertFalse(FixQualityPolicy.accept(prev, net(53.00001, -2, 3000, 20)));
    }

    @Test public void networkFixUsedWhenGpsHasGoneQuiet() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        // Past the freshness window the network fix is allowed, if it is good enough.
        assertTrue(FixQualityPolicy.accept(prev, net(53.00001, -2, 20_000, 30)));
    }

    @Test public void poorFixRejectedWhileGoodFixIsRecent() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        assertFalse(FixQualityPolicy.accept(prev, gps(53.00001, -2, 3000, 80)));
    }

    @Test public void poorFixUsedAsFallbackAfterQuietPeriod() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        assertTrue(FixQualityPolicy.accept(prev, gps(53.00001, -2, 12_000, 80)));
        // Still too poor even as a fallback.
        assertFalse(FixQualityPolicy.accept(prev, gps(53.00001, -2, 12_000, 180)));
    }

    @Test public void rejectsTeleportingFix() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        // Roughly 11 km in 2 s: implausible.
        assertFalse(FixQualityPolicy.accept(prev, gps(53.1, -2, 2000, 5)));
    }

    @Test public void acceptsNormalDrivingSpeed() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        // About 30 m/s (67 mph) north over 1 s.
        assertTrue(FixQualityPolicy.accept(prev, gps(53 + 30.0 / 111_320.0, -2, 1000, 5)));
    }

    @Test public void reacquiresAfterLongGapEvenIfFarAway() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        assertTrue(FixQualityPolicy.accept(prev, gps(53.1, -2, 60_000, 5)));
    }

    private static FixQualityPolicy.Sample fused(double lat, double lon, long t, float acc) {
        return new FixQualityPolicy.Sample(lat, lon, t, acc, FixQualityPolicy.SOURCE_FUSED);
    }

    @Test public void fusedFixAcceptedOverFreshGps() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        assertTrue(FixQualityPolicy.accept(prev, fused(53.00001, -2, 1000, 4)));
    }

    @Test public void rawGpsIgnoredWhileFusedIsFresh() {
        FixQualityPolicy.Sample prev = fused(53, -2, 0, 4);
        assertFalse(FixQualityPolicy.accept(prev, gps(53.00001, -2, 1000, 5)));
        assertFalse(FixQualityPolicy.accept(prev, net(53.00001, -2, 1000, 20)));
    }

    @Test public void rawGpsTakesOverWhenFusedGoesQuiet() {
        FixQualityPolicy.Sample prev = fused(53, -2, 0, 4);
        assertTrue(FixQualityPolicy.accept(prev,
                gps(53.00001, -2, FixQualityPolicy.GPS_FRESH_MS + 1000, 5)));
    }

    @Test public void preciseSatelliteCanReplacePoorFusedFix(){
        assertTrue(FixQualityPolicy.accept(fused(53,-2,1000,45),gps(53,-2,2000,8)));
        assertFalse(FixQualityPolicy.accept(gps(53,-2,1000,8),fused(53,-2,2000,45)));
        assertFalse(FixQualityPolicy.accept(fused(53,-2,1000,45),net(53,-2,2000,8)));
    }
    @Test public void booleanShorthandMapsToGpsAndNetworkSources() {
        assertEquals(FixQualityPolicy.SOURCE_GPS, gps(53, -2, 0, 5).source);
        assertEquals(FixQualityPolicy.SOURCE_NETWORK, net(53, -2, 0, 5).source);
    }

    @Test public void ageUsesMonotonicClockWhenAvailable() {
        // Wall clock is 8 s behind the fix's own time, but the fix is 2 s old on the monotonic clock.
        assertEquals(2000L, FixQualityPolicy.ageMs(98_000_000_000L, 1_008_000L, 100_000L, 1_000_000L));
    }

    @Test public void ageFallsBackToWallClockWithoutElapsedStamp() {
        assertEquals(3000L, FixQualityPolicy.ageMs(0L, 997_000L, 50L, 1_000_000L));
    }

    @Test public void ageIsNeverNegative() {
        assertEquals(0L, FixQualityPolicy.ageMs(0L, 1_005_000L, 0L, 1_000_000L));
        assertEquals(0L, FixQualityPolicy.ageMs(101_000_000_000L, 0L, 100_000L, 0L));
    }
}
