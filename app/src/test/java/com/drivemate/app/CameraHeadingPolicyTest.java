package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CameraHeadingPolicyTest {
    @Test public void directionUpKeepsVehiclePointingAtTop() {
        assertEquals(0, CameraHeadingPolicy.screenDegrees(90, 90), 0.001);
        assertEquals(0, CameraHeadingPolicy.rasterMarkerRotation(270, 270), 0.001);
    }

    @Test public void northUpPointsEastAndWestCorrectly() {
        assertEquals(90, CameraHeadingPolicy.screenDegrees(90, 0), 0.001);
        assertEquals(-90, CameraHeadingPolicy.screenDegrees(270, 0), 0.001);
        assertEquals(-90, CameraHeadingPolicy.rasterMarkerRotation(90, 0), 0.001);
        assertEquals(90, CameraHeadingPolicy.rasterMarkerRotation(270, 0), 0.001);
    }

    @Test public void vehicleRemainsCorrectWhileCameraTurns() {
        assertEquals(30, CameraHeadingPolicy.screenDegrees(120, 90), 0.001);
        assertEquals(-30, CameraHeadingPolicy.rasterMarkerRotation(120, 90), 0.001);
        assertEquals(20, CameraHeadingPolicy.screenDegrees(10, 350), 0.001);
    }

    @Test public void bearingNeedsSpeedAndBearing() {
        assertTrue(CameraHeadingPolicy.usableBearing(true, true, 5f, Float.NaN));
        assertFalse(CameraHeadingPolicy.usableBearing(false, true, 5f, Float.NaN));
        assertFalse(CameraHeadingPolicy.usableBearing(true, false, 5f, Float.NaN));
        assertFalse(CameraHeadingPolicy.usableBearing(true, true, 1.5f, Float.NaN));
        assertFalse(CameraHeadingPolicy.usableBearing(true, true, Float.NaN, Float.NaN));
    }

    @Test public void bearingRejectedWhenDeviceReportsWideUncertainty() {
        assertTrue(CameraHeadingPolicy.usableBearing(true, true, 8f, 30f));
        assertFalse(CameraHeadingPolicy.usableBearing(true, true, 8f, 45f));
        assertTrue(CameraHeadingPolicy.usableBearing(true, true, 8f, -1f));
    }
}
