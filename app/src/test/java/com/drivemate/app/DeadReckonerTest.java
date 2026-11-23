package com.drivemate.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class DeadReckonerTest {
    private static final GeoMath.Point START = new GeoMath.Point(53.0, -2.0);

    @Test public void noEstimateBeforeAnyFix() {
        assertNull(new DeadReckoner().estimate(1_000_000L));
    }

    @Test public void noEstimateWhenStationary() {
        DeadReckoner dr = new DeadReckoner();
        dr.onFix(START, 1000, 0.5f, 0, true);
        assertNull(dr.estimate(4000));
    }

    @Test public void noEstimateWithoutBearing() {
        DeadReckoner dr = new DeadReckoner();
        dr.onFix(START, 1000, 15f, 0, false);
        assertNull(dr.estimate(4000));
    }

    @Test public void noEstimateForNormalFixSpacing() {
        DeadReckoner dr = new DeadReckoner();
        dr.onFix(START, 1000, 15f, 0, true);
        assertNull(dr.estimate(1000 + DeadReckoner.MIN_GAP_MS - 1));
    }

    @Test public void extrapolatesNorthAlongBearing() {
        DeadReckoner dr = new DeadReckoner();
        dr.onFix(START, 0, 10f, 0, true);
        // 3 s at 10 m/s due north is about 30 m.
        GeoMath.Point p = dr.estimate(3000);
        assertNotNull(p);
        assertEquals(START.lon, p.lon, 1e-9);
        assertEquals(30.0, GeoMath.distance(START, p), 0.5);
        assertTrue(p.lat > START.lat);
    }

    @Test public void extrapolatesEastAlongBearing() {
        DeadReckoner dr = new DeadReckoner();
        dr.onFix(START, 0, 10f, 90, true);
        GeoMath.Point p = dr.estimate(3000);
        assertNotNull(p);
        assertTrue(p.lon > START.lon);
        assertEquals(30.0, GeoMath.distance(START, p), 0.5);
    }

    @Test public void stopsExtrapolatingAfterMaxBridge() {
        DeadReckoner dr = new DeadReckoner();
        dr.onFix(START, 0, 10f, 0, true);
        assertNull(dr.estimate(DeadReckoner.MAX_BRIDGE_MS + 1));
    }

    @Test public void resetClearsAnchor() {
        DeadReckoner dr = new DeadReckoner();
        dr.onFix(START, 0, 10f, 0, true);
        dr.reset();
        assertNull(dr.estimate(3000));
    }
}
