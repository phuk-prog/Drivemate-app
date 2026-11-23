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
}
