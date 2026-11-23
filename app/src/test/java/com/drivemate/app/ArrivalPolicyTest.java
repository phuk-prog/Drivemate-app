package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArrivalPolicyTest {
    @Test public void oneGpsFixMustNotFinishJourney() {
        ArrivalPolicy a = new ArrivalPolicy();
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                18, 35, 4, 6, 0.4f, 10000, 10050));
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                18, 35, 4, 6, 0.4f, 10000, 10100));
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                18, 35, 4, 6, 0.4f, 11000, 11050));
        assertEquals(ArrivalPolicy.State.ARRIVED, a.observe(
                18, 35, 4, 6, 0.4f, 12000, 12050));
    }
    @Test public void parallelRoadAndImpreciseGpsBlockArrival() {
        ArrivalPolicy a = new ArrivalPolicy();
        assertEquals(ArrivalPolicy.State.APPROACHING, a.observe(
                22, 34, 74, 8, 0.4f, 10000, 10100));
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                24, 45, 6, 40, 0.4f, 11000, 11100));
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                24, 45, 6, 6, 3, 12000, 12100));
        assertEquals(ArrivalPolicy.State.EN_ROUTE, a.observe(
                14, 22, 4, 5, 0, 20000, 30000));
    }
    @Test public void approachHintStillWorksWithoutGpsSpeed() {
        ArrivalPolicy a = new ArrivalPolicy();
        assertEquals(ArrivalPolicy.State.APPROACHING, a.observe(
                180, 220, 15, 25, Float.POSITIVE_INFINITY, 10000, 10100));
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                35, 55, 5, 15, Float.POSITIVE_INFINITY, 11000, 11100));
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                18, 34, 5, 15, Float.POSITIVE_INFINITY, 12000, 12100));
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                18, 34, 5, 15, Float.POSITIVE_INFINITY, 13000, 13100));
    }

    @Test public void resetAndDistanceGuards() {
        ArrivalPolicy a = new ArrivalPolicy();
        a.observe(15, 30, 5, 5, 0, 10000, 10100);
        a.observe(15, 30, 5, 5, 0, 11000, 11100);
        a.reset();
        assertEquals(ArrivalPolicy.State.NEARBY, a.observe(
                15, 30, 5, 5, 0, 12000, 12100));
        assertEquals(ArrivalPolicy.State.APPROACHING, a.observe(
                250, 290, 7, 15, 5, 13000, 13100));
        assertEquals(ArrivalPolicy.State.EN_ROUTE, a.observe(
                1000, 1500, 7, 15, 5, 14000, 14100));
    }
    @Test public void parkedCarNeedsPreciseStationaryFix() {
        assertTrue(ArrivalPolicy.canSaveParking(10, 0, 10000, 12000));
        assertFalse(ArrivalPolicy.canSaveParking(50, 0, 10000, 12000));
        assertFalse(ArrivalPolicy.canSaveParking(10, 5, 10000, 12000));
        assertFalse(ArrivalPolicy.canSaveParking(10, 0, 10000, 20000));
        assertFalse(ArrivalPolicy.canSaveParking(Float.NaN, 0, 10000, 12000));
    }
    @Test public void roadSideComesOnlyFromTomTomArrivalCode() {
        assertEquals("Destination on your left", ArrivalPolicy.sideLabel("ARRIVE_LEFT"));
        assertEquals("Destination on your right", ArrivalPolicy.sideLabel("arrive_right"));
        assertNull(ArrivalPolicy.sideLabel("ARRIVE"));
        assertNull(ArrivalPolicy.sideLabel("TURN_LEFT"));
        assertNull(ArrivalPolicy.sideLabel(null));
    }

    @Test public void verifiedEntranceMustBeActualNearbyCoordinate() {
        GeoMath.Point place = new GeoMath.Point(51.5000, -0.1000);
        assertTrue(ArrivalPolicy.trustedEntrance(new GeoMath.Point(51.5002, -0.1002), place));
        assertFalse(ArrivalPolicy.trustedEntrance(new GeoMath.Point(51.55, -0.10), place));
        assertFalse(ArrivalPolicy.trustedEntrance(null, place));
    }
}
